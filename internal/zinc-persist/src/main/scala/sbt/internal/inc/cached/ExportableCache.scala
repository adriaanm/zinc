/*
 * Zinc - The incremental compiler for Scala.
 * Copyright 2011 - 2017, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * This software is released under the terms written in LICENSE.
 */

package sbt.internal.inc.cached

import java.io.File
import java.nio.file.Path

import sbt.internal.inc._
import sbt.io.{ PathFinder, IO }
import xsbti.compile.{ CompileAnalysis, MiniSetup, SingleOutput }

class ExportableCacheMapper(root: Path) extends AnalysisMappersAdapter {
  private def justRelativize = Mapper.relativizeFile(root)

  override val outputDirMapper: Mapper[File] = justRelativize
  override val sourceDirMapper: Mapper[File] = justRelativize
  override val sourceMapper: Mapper[File] = justRelativize
  override val productMapper: Mapper[File] = justRelativize
  override val binaryMapper: Mapper[File] = justRelativize
  override val classpathMapper: Mapper[File] = justRelativize

  override val binaryStampMapper: ContextAwareMapper[File, Stamp] =
    Mapper.updateModificationDateFileMapper(binaryMapper)
}

sealed trait CleanOutputMode
case object CleanOutput extends CleanOutputMode
case object FailOnNonEmpty extends CleanOutputMode
case object CleanClasses extends CleanOutputMode

class ExportableCache(val cacheLocation: Path, cleanOutputMode: CleanOutputMode = CleanOutput)
    extends CompilationCache {

  val analysisFile: Path = cacheLocation.resolve("analysis.zip")
  val classesZipFile: Path = cacheLocation.resolve("classes.zip")

  protected def createMapper(projectLocation: File) =
    new ExportableCacheMapper(projectLocation.toPath)

  protected def cacheVerifier(): CacheVerifier = NoopVerifier

  protected def outputDirFor(setup: MiniSetup): File = setup.output() match {
    case single: SingleOutput =>
      single.outputDirectory()
    case _ => throw new RuntimeException("Only single output is supported")
  }

  override def loadCache(projectLocation: File): Option[(CompileAnalysis, MiniSetup)] = {
    val store = FileBasedStore(analysisFile.toFile, createMapper(projectLocation))

    for ((newAnalysis: Analysis, newSetup) <- store.get()) yield {

      val importedClassFiles = importBinaryCache(newAnalysis, newSetup)
      val analysisForLocalProducts =
        updateStampsForImportedProducts(newAnalysis, importedClassFiles)

      (analysisForLocalProducts, newSetup)
    }
  }

  private def updateStampsForImportedProducts(analysis: Analysis,
                                              importedFiles: Set[File]): Analysis = {
    val oldStamps = analysis.stamps

    val updatedProducts = oldStamps.products.map {
      case (file, stamp) if importedFiles.contains(file) =>
        (file, Stamp.lastModified(file))
      case other => other
    }

    val newStamps = Stamps(
      products = updatedProducts,
      sources = oldStamps.sources,
      binaries = oldStamps.binaries
    )

    analysis.copy(stamps = newStamps)
  }

  def exportCache(projectLocation: File,
                  currentAnalysisStore: AnalysisStore): Option[VerficationResults] = {
    for ((currentAnalysis: Analysis, currentSetup) <- currentAnalysisStore.get()) yield {
      val verifier = cacheVerifier()
      val mapper = verifier.verifingMappers(createMapper(projectLocation))
      val remoteStore = FileBasedStore(analysisFile.toFile, mapper)

      exportBinaryCache(currentAnalysis, currentSetup)

      remoteStore.set(currentAnalysis, currentSetup)
      verifier.results
    }
  }

  protected def cleanOutput(output: File): Unit = {
    if (output.exists()) {
      if (output.isDirectory) {
        cleanOutputMode match {
          case CleanOutput =>
            if (output.list().nonEmpty) IO.delete(output)
          case FailOnNonEmpty =>
            if (output.list().nonEmpty)
              throw new IllegalStateException(
                s"Output directory: $output is not empty and cleanOutput is false")
          case CleanClasses =>
            val classFiles = PathFinder(output) ** "*.class"
            IO.delete(classFiles.get)
        }
      } else throw new IllegalStateException(s"Output file: $output is not a directory")
    }
  }

  protected def importBinaryCache(newAnalysis: Analysis, newSetup: MiniSetup): Set[File] = {
    val output = outputDirFor(newSetup)
    cleanOutput(output)

    IO.unzip(classesZipFile.toFile, output, preserveLastModified = true)
  }

  protected def exportBinaryCache(currentAnalysis: Analysis, currentSetup: MiniSetup): Unit = {
    val out = outputDirFor(currentSetup).toPath

    def files(f: File): List[File] =
      f :: (if (f.isDirectory) IO.listFiles(f).toList.flatMap(files) else Nil)

    val entries = files(out.toFile).map { classFile =>
      val mapping = out.relativize(classFile.toPath).toString
      classFile -> mapping
    }

    IO.zip(entries, classesZipFile.toFile)
  }
}
