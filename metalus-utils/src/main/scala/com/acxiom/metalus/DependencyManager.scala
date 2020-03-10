package com.acxiom.metalus

import java.io.{File, FileOutputStream}
import java.nio.file.Files
import java.util.jar.JarFile

import com.acxiom.metalus.resolvers.{Dependency, DependencyResolver}
import com.acxiom.pipeline.fs.{FileManager, LocalFileManager}
import com.acxiom.pipeline.utils.{DriverUtils, ReflectionUtils}
import org.apache.log4j.Logger

object DependencyManager {

  def main(args: Array[String]): Unit = {
    val parameters = DriverUtils.extractParameters(args, Some(List("jar-files", "output-path")))
    val localFileManager = new LocalFileManager
    // Get the output directory
    val output = new File(parameters.getOrElse("output-path", "jars").asInstanceOf[String])
    if (!output.exists()) {
      output.mkdirs()
    }
    // Initialize the Jar files
    val noAuthDownload = parameters.getOrElse("no-auth-download", "false") == "true"
    val fileList = parameters("jar-files").asInstanceOf[String].split(",").toList
    val initialClassPath = fileList.foldLeft(ResolvedClasspath(List()))((cp, file) => {
      val fileName = file.substring(file.lastIndexOf("/") + 1)
      val artifactName = fileName.substring(0, fileName.lastIndexOf("."))
      val srcFile = if (file.startsWith("http")) {
        val http = DriverUtils.getHttpRestClient(file, parameters, Some(noAuthDownload))
        val input = http.getInputStream("")
        val dir = Files.createTempDirectory("metalusJarDownloads").toFile
        val localFile = new File(dir, fileName)
        localFile.deleteOnExit()
        dir.deleteOnExit()
        new LocalFileManager().copy(input, new FileOutputStream(localFile), FileManager.DEFAULT_COPY_BUFFER_SIZE, closeStreams = true)
        localFile
      } else {
        new File(file)
      }
      val destFile = new File(output, fileName)
      copyStepJarToLocal(localFileManager, new JarFile(srcFile), destFile)
      cp.addDependency(Dependency(artifactName, artifactName.split("-")(1), destFile))
    })
    // Get the dependencies
    val dependencies = resolveDependencies(initialClassPath.dependencies, output, parameters)
    // Build out the classpath
    val classpath = dependencies.foldLeft(initialClassPath)((cp, dep) => cp.addDependency(dep))
    val pathPrefix = parameters.getOrElse("path-prefix", "").asInstanceOf[String]
    // Print the classpath to the console
    print(classpath.generateClassPath(pathPrefix, parameters.getOrElse("jar-separator", ",").asInstanceOf[String]))
  }

  private def copyStepJarToLocal(localFileManager: LocalFileManager, jar: JarFile, outputFile: File) = {
    if (!outputFile.exists()) {
      val outputStream = localFileManager.getOutputStream(outputFile.getAbsolutePath)
      localFileManager.copy(localFileManager.getInputStream(jar.getName), outputStream, FileManager.DEFAULT_COPY_BUFFER_SIZE, closeStreams = true)
    }
  }

  private def resolveDependencies(dependencies: List[Dependency], output: File, parameters: Map[String, Any]): List[Dependency] = {
    dependencies.foldLeft(List[Dependency]())((deps, dep) => {
      val dependencyList = resolveDependency(dep, output, parameters)
      if (dependencyList.nonEmpty) {
        val childDeps = resolveDependencies(dependencyList, output, parameters)
        if (childDeps.nonEmpty) {
          deps ::: dependencyList ::: childDeps
        } else {
          deps ::: dependencyList
        }
      } else {
        deps
      }
    })
  }

  private def resolveDependency(dependency: Dependency, output: File, parameters: Map[String, Any]): List[Dependency] = {
    val dependencyMap: Option[Map[String, Any]] = DependencyResolver.getDependencyJson(dependency.localFile.getAbsolutePath, parameters)
    if (dependencyMap.isDefined) {
      // Currently only support one dependency within the json
      val dependencyType = dependencyMap.get.head._1
      val resolverName = s"com.acxiom.metalus.resolvers.${dependencyType.toLowerCase.capitalize}DependencyResolver"
      val resolver = ReflectionUtils.loadClass(resolverName).asInstanceOf[DependencyResolver]
      resolver.copyResources(output, dependencyMap.get(dependencyType).asInstanceOf[Map[String, Any]])
    } else {
      List()
    }
  }
}

case class ResolvedClasspath(dependencies: List[Dependency]) {
  private val logger = Logger.getLogger(getClass)

  def generateClassPath(jarPrefix: String, separator: String = ","): String = {
    val prefix = if (jarPrefix.endsWith("/")) {
      jarPrefix
    } else {
      s"$jarPrefix/"
    }
      dependencies.foldLeft("")((cp, dep) => s"$cp$prefix${dep.localFile.getName}$separator").dropRight(1)
  }

  def addDependency(dependency: Dependency): ResolvedClasspath = {
    if (dependencies.exists(dep => dep.name == dependency.name)) {
      ResolvedClasspath(dependencies.map(dep => {
        if (dep.name == dependency.name) {
          val version1 = dep.version
          val version2 = dependency.version
          val finalDependency = if (version1.split("\\.")
            .zipAll(version2.split("\\."), "0", "0")
            .find { case (a, b) => a != b }
            .fold(0) {
              case (a, b) if a.forall(_.isDigit) && b.forall(_.isDigit) => a.toInt - b.toInt
              case a if a._1.forall(_.isDigit) => 1
              case (a, b) => a.compareTo(b)
            } > 0) {
            dep
          } else {
            dependency
          }
          logger.warn(s"Found two versions of ${dependency.name} (${dep.version} / ${dependency.version}) using ${finalDependency.version}")
          finalDependency
        } else {
          dep
        }
      }))
    } else {
      ResolvedClasspath(dependencies :+ dependency)
    }
  }
}