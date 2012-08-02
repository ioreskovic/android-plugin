import sbt._
import Keys._
import AndroidKeys._
import AndroidHelpers._

import java.io.{ File => JFile }
import java.io.{ Writer => JWriter, PrintWriter => JPrintWriter }
import java.io.FileNotFoundException
import java.lang.{ System => JSystem }
import java.util.Enumeration

import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import java.io.FileOutputStream
import java.io.FileInputStream

import scala.collection.mutable.{ HashMap => MHashMap }
import scala.collection.mutable.{ HashSet => MHashSet }
import scala.collection.JavaConversions._
import scala.util.Properties._


/**
 * Provides functionality for pushing and removing different Scala versions to and from a rooted device.<br>
 * Scala version being pushed is the same one the project is written in.<br>
 */
object RootScalaInstaller {

	/**
	 * Tries to create a permissions xml file for the Scala library version specified.<br>
	 * If specified scala version is not present locally, an attempt to download it is made.<br>
	 * If there is no such version to download, no actions are done and user is notified.<br>
	 * <br>
	 * @param version the Scala version specified
	 * @return <code>true</true> if the the specified Scala version exists (before or after the download) and the permissions file is generated. <code>false</code> otherwise.
	 */
	private def makePermission(version: String): Boolean = {  
		val home = userHome
		val scalaFolder = new JFile(home + "/.sbt/boot/scala-" + version)

		if (!scalaFolder.exists) {
			println("[Root Scala Installer] `" + scalaFolder.getAbsolutePath + "` does not exist!")
			return false
		}
		
		val permission = new JFile(scalaFolder, "scala-library.xml")
		
		if (!permission.exists) {
			val writer = new JPrintWriter(permission)
			
			writer.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
			writer.println("")
			writer.println("<permissions>")
			writer.println("    <library")
			writer.println("        name=\"scala-library-" + version + "\"")
			writer.println("        file=\"/data/data/" + mainPackage + "/files/scala-library-" + version + ".jar\"")
			writer.println("    />")
			writer.println("</permissions>")
			
			writer.close
		}
		
		return true
	}

	/**
	* Checks whether the input files are up to date in regard to the output file.
	*
	* @param inputs files to be compared against the output file
	* @param output file being compared to the input files
	* @return <code>true</code> if all the input files were modified earlier than the output file. Otherwise, <code>false</code>
	*/
	def isUpToDate(inputs: Seq[JFile], output: JFile): Boolean = {
		val upToDate = output.exists && inputs.forall(input =>
			input.isDirectory match {
				case true =>
					(input ** "*").get.forall(_.lastModified <= output.lastModified)
				case false =>
					input.lastModified <= output.lastModified
			}
		)
		upToDate
	}
	
	/**
	 * Extracts zip subpackages matching prefix filter from source zip file and packs it in destination zip file.
	 *
	 * @param src the source zip file
	 * @param dest the destination zip file
	 * @param prefix the prefix filter for entry matching
	 */
	private def repackJarCollection(src: String, dest: String, prefix: String) {
		
		val source = new JFile(src)
		val destination = new JFile(dest)
		
		val in = new ZipInputStream(new FileInputStream(source))
		val out = new ZipOutputStream(new FileOutputStream(destination))
		
		var entry: ZipEntry = in.getNextEntry
		
		while (entry != null) {
			if (entry.getName.startsWith(prefix) && !(entry.getName.startsWith("scala/collection/immutable") || entry.getName.startsWith("scala/collection/mutable"))) {
				out.putNextEntry(entry)
				val buffer = new Array[Byte](4096)
				Iterator.continually(in.read(buffer))
						.takeWhile(_ != -1)
						.foreach {
							out.write(buffer, 0, _)
						}
				out.closeEntry
			}
			entry = in.getNextEntry
		}
		
		in.close
		out.close
	}
	
	/**
	 * Extracts zip subpackages matching prefix filter from source zip file and packs it in destination zip file.
	 *
	 * @param src the source zip file
	 * @param dest the destination zip file
	 * @param prefix the prefix filter for entry matching
	 */
	private def repackJarPart(src: String, dest: String, prefix: String) {
		
		val source = new JFile(src)
		val destination = new JFile(dest)
		
		val in = new ZipInputStream(new FileInputStream(source))
		val out = new ZipOutputStream(new FileOutputStream(destination))
		
		var entry: ZipEntry = in.getNextEntry
		
		while (entry != null) {
			if (entry.getName.startsWith(prefix)) {
				out.putNextEntry(entry)
				val buffer = new Array[Byte](4096)
				Iterator.continually(in.read(buffer))
						.takeWhile(_ != -1)
						.foreach {
							out.write(buffer, 0, _)
						}
				out.closeEntry
			}
			entry = in.getNextEntry
		}
		
		in.close
		out.close
	}
	
	/**
	 * Extracts zip subpackages matching prefix filter from source zip file and packs it in destination zip file.<br>
	 * Prefix filter must match root package of the source zip file.<br>
	 *
	 * @param src the source zip file
	 * @param dest the destination zip file
	 * @param prefix the prefix filter for entry matching
	 */
	private def repackJarRoot(src: String, dest: String, prefix: String) {
		
		val source = new JFile(src)
		val destination = new JFile(dest)
		
		val in = new ZipInputStream(new FileInputStream(source))
		val out = new ZipOutputStream(new FileOutputStream(destination))
		
		var entry: ZipEntry = in.getNextEntry
		
		while (entry != null) {
			if (entry.getName.startsWith(prefix) && !entry.getName.substring(prefix.length + 1).contains("/")) {
				out.putNextEntry(entry)
				val buffer = new Array[Byte](4096)
				Iterator.continually(in.read(buffer))
						.takeWhile(_ != -1)
						.foreach {
							out.write(buffer, 0, _)
						}
				out.closeEntry
			}
			entry = in.getNextEntry
		}
		
		in.close
		out.close
	}
	
	/**
	 * Returns a sequence of scala library packages in a specified scala library jar file
	 *
	 * @param scalaLibraryFile the specified scala library jar file
	 * @return sequence of scala library packages
	 */
	private def getScalaLibraryPackages(scalaLibraryFile: JFile, version: String): Seq[String] = {
	
		val home = userHome
		val scalaLibraryFile = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/scala-library.jar")
		val scalaJarsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/jars")
		val scalaDexsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/dexs")
		val scalaXmlsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/xmls")
		val scalaLibsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/libs")
		
		val entries: Iterator[ZipEntry] = new ZipFile(scalaLibraryFile).entries
		var packageMap = new MHashMap[String, MHashSet[ZipEntry]]
		
		for (e <- entries) {
			if (e.getName.startsWith("scala/")) {
				val noRoot = e.getName.substring(6)
				
				var key: String = new String()
				
				if (noRoot.startsWith("collection/immutable") || noRoot.startsWith("collection/mutable")) {
					val hierarchy = e.getName.split("/")
					key = hierarchy(0) + "/" + hierarchy(1) + "/" + hierarchy(2)
				} else if (noRoot.contains("/")) {
					val hierarchy = e.getName.split("/")
					key = hierarchy(0) + "/" + hierarchy(1)
				} else {
					key = "scala"
				}
				
				var subPackage = packageMap.getOrElse(key, MHashSet.empty[ZipEntry])
				subPackage += e
				
				if (!packageMap.contains(key)) {
					packageMap.put(key, subPackage)
				}
			}
		}
		
		packageMap.keys.toSeq
	}
	
	/**
	 * Lists all the files in the given directory recursively.
	 * 
	 * @param f the directory to be inspected
	 * @return list of files under in the specified directory
	 */
	def recursiveListFiles(f: File): Array[File] = {
		val these = f.listFiles
		if (these != null) {
			these ++ these.filter(_.isDirectory).flatMap(recursiveListFiles) 
		} else {
			Array[File]()
		}
	}
	
	/**
	 * Checks if the connected device is rooted.<br>
	 *
	 * @return <code>true</code> if the connected device is rooted. Otherwise, <code>false</code>
	 */
	private def checkRoot(dbPath: JFile, streams: TaskStreams): Boolean = {
		if (checkRootMethodWithWritePermission(dbPath.getAbsolutePath, streams)) {
			return true
		} else {
			return false
		}
	}
	
	/**
	 * Checks whether the device is rooted by examining if a <code>write</code> permission exists on root(<code>/</code>).<br>
	 *
	 * @return <code>true</code> if a <code>write</code> permission exists on root(<code>/</code>). <code>false</code> otherwise
	 */
	private def checkRootMethodWithWritePermission(dbPath: String, streams: TaskStreams): Boolean = {
		
		val (exit, response) = adbTaskWithOutput(
			dbPath, 
			false, 
			streams, 
			"shell", 
			"[ -w / ] && echo Rooted || echo Not Rooted"
		)
		
		if (response.trim.equals("Rooted")) {
			return true
		} else {
			return false
		}
	}
	
	/**
	 * Attempts to push the project-specified Scala version to the connected device. The device needs to be rooted for this operation to succeed.<br>
	 * <br>
	 * Pushing s Scala library consists of copying actual Scala library to <code>/system/framework/scala-library-{VERSION}.jar</code> and associated persmission to <code>/system/etc/permissions/scala-library-{VERSION}.xml</code><br>
	 */
	def pushScala() = (dbPath, streams, scalaVersion, rootScalaVersion) map {
		              (dbPath, streams, version, installedVersions) =>
		
		val isRooted = checkRoot(dbPath, streams)
		val home = userHome
		val scalaLibraryFile = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/scala-library.jar")
		val scalaJarsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/jars")
		val scalaDexsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/dexs")
		val scalaXmlsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/xmls")
		val scalaLibsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/libs")
		val sep = JSystem.getProperty("file.separator")
		
		if (isRooted) {
			streams.log.info("[Development] Rooted device detected. Preparing to push Scala " + version + " to device...")
			
			val scalaVersionExists = makePermission(version)
			
			if (scalaVersionExists) {
			
				if (installedVersions.contains(version) && (installedVersions.size == 1)) {
					streams.log.info("[Development] Scala library version: " + version + " is already present. Skipping...")
				} else {
					streams.log.info("[Development] Scala " + version + " found")
					streams.log.info("[Development] Trying to push Scala " + version + " to device")
					
					streams.log.info("[Development] Remounting device...")
					adbTask(dbPath.getAbsolutePath, false, streams, "remount")
					
					for (installedVersion <- installedVersions) {
						if (!installedVersion.equals(version)) {
							streams.log.info("[Development] Removing Scala library version: " + installedVersion + "...")
							
							adbTask(
								dbPath.getAbsolutePath, false, streams, 
								"shell",
								"rm -f",
								"/system/framework/scala-*" + installedVersion + ".jar"
							)
							
							adbTask(
								dbPath.getAbsolutePath, false, streams, 
								"shell",
								"rm -f",
								"/system/etc/permissions/scala-*" + installedVersion +  ".xml"
							)
						}
					}
					
					if (!installedVersions.contains(version)) {
						streams.log.info("[Development] Pushing Scala library version: " + version + "...")
						for (file <- scalaLibsDir.listFiles) {
							if (file.getName.endsWith(".jar")) {
								adbTask(
									dbPath.getAbsolutePath, false, streams, 
									"push", 
									home + sep + ".sbt" + sep + "boot" + sep + "scala-" + version + sep + "lib" + sep + "libs" + sep + file.getName, 
									"/system/framework/" + file.getName
								)
								
								adbTask(
									dbPath.getAbsolutePath, false, streams, 
									"shell",
									"su -c",
									"\"chmod 666 /system/framework/" + file.getName + "\""
								)
							}
						}
						
						for (file <- scalaXmlsDir.listFiles) {
							if (file.getName.endsWith(".xml")) {
								adbTask(
									dbPath.getAbsolutePath, false, streams, 
									"push", 
									home + sep + ".sbt" + sep + "boot" + sep + "scala-" + version + sep + "lib" + sep + "xmls" + sep + file.getName, 
									"/system/etc/permissions/" + file.getName
								)
								
								adbTask(
									dbPath.getAbsolutePath, false, streams, 
									"shell", 
									"su -c",
									"\"ln -s /system/etc/permissions/" + file.getName + " /system/etc/permissions/" + file.getName + "\""
								)
							}
						}
					}
					
					streams.log.info("[Development] Rebooting device...")
					adbTask(dbPath.getAbsolutePath, false, streams, "reboot")
					
				}
				
			} else {
				streams.log.info("[Development] Scala " + version + " not found. Please download Scala library " + version + ".")
			}
		} else {
			streams.log.info("[Development] Your device is not rooted. This operations is supported by rooted phones only.")
		}
	}
	
	/**
	 * Attempts to remove all the Scala versions from the connected device. The device needs to be rooted for this operation to succeed.<br>
	 * <br>
	 * Removing s Scala library consists of removing actual Scala library <code>/system/framework/scala-library-{VERSION}.jar</code> and associated persmission <code>/system/etc/permissions/scala-library-{VERSION}.xml</code><br>
	 */
	def removeScala() = (dbPath, streams, rootScalaVersion) map {
	                    (dbPath, streams, installedVersions) =>
		
		val isRooted = checkRoot(dbPath, streams)
		
		if (isRooted && !installedVersions.isEmpty) {
			streams.log.info("[Development] Rooted device detected. Attempting to remove Scala libraries from device: " + installedVersions.mkString(", ") + " ...")
			
			streams.log.info("[Development] Remounting device ...")
			adbTask(dbPath.getAbsolutePath, false, streams, "remount")
			
			for (installedVersion <- installedVersions) {
				streams.log.info("[Development] Removing Scala library version: " + installedVersion + " ...")
				
				adbTask(
					dbPath.getAbsolutePath, false, streams, 
					"shell",
					"rm -f",
					"/system/framework/scala-*" + installedVersion + ".jar"
				)
				
				adbTask(
					dbPath.getAbsolutePath, false, streams, 
					"shell",
					"rm -f",
					"/system/etc/permissions/scala-*" + installedVersion +  ".xml"
				)
			}
			
			streams.log.info("[Development] Rebooting device ...")
			adbTask(dbPath.getAbsolutePath, false, streams, "reboot")
		} else if (isRooted && installedVersions.isEmpty) {
			streams.log.info("[Development] Rooted device detected. No Scala library detected.")
		} else {
			streams.log.info("[Development] Your device is not rooted. This operations is supported by rooted phones only.")
		}
	}
	
	/**
	 * Checks if there is already a Scala library on the rooted device and saves that as a setting.<br>
	 * <br>
	 * The Scala library instance should be located in <code>/system/framework</code>.<br>
	 */
	def checkScala(): Project.Initialize[Task[Seq[String]]] = (dbPath, streams) map {
	                                                          (dbPath, streams) =>
		
		val isRooted = checkRoot(dbPath, streams)
		
		if (isRooted) {
			streams.log.info("[Development] Rooted device detected. Checking Scala version on device ...")
			
			val (exit, response) = adbTaskWithOutput(
				dbPath.getAbsolutePath, false, streams, 
				"shell",
				"ls /system/framework/scala-*.jar"
			)
			
			if ((exit != 0) || (response.contains("Failure"))) {
				streams.log.info("[Development] Error executing ADB.")
				Seq.empty[String]
			} else if (response.contains("No such file") || !response.contains("/system/framework/scala-")) {
				streams.log.info("[Development] No Scala library detected on device")
				Seq.empty[String]
			} else {
				var versions = Seq.empty[String]
				val lines = response.split("\n")
				
				for (line <- lines) {
					val v = line.split("/system/framework/")(1).split("-").last.split(".jar")(0)
					versions = v +: versions 
				}
				
				versions.toSet.toSeq.foreach((v: String) => streams.log.info("[Development] Found Scala Library version: " + v))
				versions.toSet.toSeq
			}

		} else {
			streams.log.info("[Development] Your device is not rooted. This operations is supported by rooted phones only.")
			Seq.empty[String]
		}
		
	}
	
	/**
	 * Finds the package name where Main Activity is located in.
	 *
	 * @return the package name where Main Activity is located in
	 */
	def getPackageName: Project.Initialize[Task[String]] = streams map { (streams) =>
		val projectFiles = recursiveListFiles(new File("."))
		val manifestFile = projectFiles
			.filter(f => f.getCanonicalPath.endsWith("AndroidManifest.xml"))
			.toList.headOption
			.getOrElse(throw new FileNotFoundException("Your project doesn't contain manifest"))

		val manifestXML = scala.xml.XML.loadFile(manifestFile)
		val pkg = manifestXML.attribute("package").getOrElse("")
		val packageName = pkg.asInstanceOf[scala.xml.Text].data
		packageName
	}
	
	/**
	 * Returns the home directory of the current user.
	 */
	private def getHomeDirectory: Project.Initialize[Task[File]] = streams map { (streams) =>
		val homeDirectory = new JFile(userHome)
		homeDirectory
	}
	
	/**
	 * Returns the folder where scala library is located. The scala folder conforms to this format: <code>{HOME}/.sbt/boot/scala-{VERSION}/lib</code><br>
	 * The home directory and scala version the current project is written in must be known for this task.<br>
	 */
	private def getScalaFolder: Project.Initialize[Task[File]] = (homeDirectory, scalaVersion) map { (homeDirectory, scalaVersion) =>
		val scalaFolder = new JFile(homeDirectory + "/.sbt/boot/scala-" + scalaVersion + "/lib")
		scalaFolder
	}
	
	/**
	 * Returns the file containing the path to the scala library jar file. The scala library file conforms to this format: <code>{HOME}/.sbt/boot/scala-{VERSION}/lib/scala-library.jar</code><br>
	 * The version of the scala library is the one the current project is written in.<br>
	 * Scala folder in <code>/.sbt/boot</code> directory must be known for this task.
	 */		
	private def getScalaLibraryFile: Project.Initialize[Task[File]] = scalaFolder map { (scalaFolder) =>
		val scalaLibraryFile = new JFile(scalaFolder, "scala-library.jar")
		scalaLibraryFile
	}
	
	/**
	 * Returns the directory where the parts of scala library are located after splitting.<br>
	 * Scala folder in <code>/.sbt/boot</code> directory must be known for this task.
	 */		
	private def getScalaJarsDirectory: Project.Initialize[Task[File]] = scalaFolder map { (scalaFolder) =>
		val scalaJarsDirectory = new JFile(scalaFolder, "jars")
		scalaJarsDirectory
	}
	
	/**
	 * Returns the directory where the dexed parts of scala library are located.<br>
	 * Scala folder in <code>/.sbt/boot</code> directory must be known for this task.
	 */		
	private def getScalaDexsDirectory: Project.Initialize[Task[File]] = scalaFolder map { (scalaFolder) =>
		val scalaDexsDirectory = new JFile(scalaFolder, "dexs")
		scalaDexsDirectory
	}
	
	/**
	 * Returns the directory where the xml permission files are located.<br>
	 * Scala folder in <code>/.sbt/boot</code> directory> must be known for this task.
	 */	
	private def getScalaXmlsDirectory: Project.Initialize[Task[File]] = scalaFolder map { (scalaFolder) =>
		val scalaXmlsDirectory = new JFile(scalaFolder, "xmls")
		scalaXmlsDirectory
	}
	
	/**
	 * Returns the directory where the repacked jar files are located.<br>
	 * Scala folder in <code>/.sbt/boot</code> directory must be known for this task.
	 */
	private def getScalaLibsDirectory: Project.Initialize[Task[File]] = scalaFolder map { (scalaFolder) =>
		val scalaLibsDirectory = new JFile(scalaFolder, "libs")
		scalaLibsDirectory
	}
	
	/**
	 * Creates the necessary directories which are used in preparation for pushing scala library as a shared library on the rooted device.<br>
	 * <br>
	 * The directories are as following<br>
	 * <ul>
	 *   <li>{HOME}/.sbt/boot/scala-{VERSION}/lib/jars - contains split scala library jars
	 *   <li>{HOME}/.sbt/boot/scala-{VERSION}/lib/dexs - contains dexed parts of split scala library
	 *   <li>{HOME}/.sbt/boot/scala-{VERSION}/lib/libs - contains repacked dex files
	 *   <li>{HOME}/.sbt/boot/scala-{VERSION}/lib/xmls - contains android library permission files for repacked dex files
	 * </ul>
	 */
	private def createScalaDirs = (scalaJarsDirectory, scalaDexsDirectory, scalaLibsDirectory, scalaXmlsDirectory) map { 
	                              (scalaJarsDirectory, scalaDexsDirectory, scalaLibsDirectory, scalaXmlsDirectory) =>

		def makeDirs(dirs: JFile*) {
			for (dir <- dirs) {
				if (!dir.exists) {
					dir.mkdirs
				}
			}
		}
		
		makeDirs(scalaJarsDirectory, scalaDexsDirectory, scalaLibsDirectory, scalaXmlsDirectory)
	}
	
	/**
	 * Splits the scala library located at <code>{HOME}/.sbt/boot/scala-{VERSION}/lib</code> directory into many smaller parts in regard to the subpackage.<br>
	 * Since the <code>scala.collection</code> package of the library is too big for a single dex file, the <code>scala.collection</code> package is divided into<br>
	 * three parts:<br>
	 * <ul>
	 *   <li><code>scala.collection.immutable</code> - contains only the classes in <code>scala.collection.immutable</code>
	 *   <li><code>scala.collection.mutable</code> - contains only the classes in <code>scala.collection.mutable</code>
	 *   <li><code>scala.collection</code> - contains the rest of the classes not in the previous two packages
	 * </ul>
	 */
	private def splitScalaLibrary = (scalaVersion, streams, scalaLibraryFile, scalaJarsDirectory) map {
	                                (scalaVersion, streams, scalaLibraryFile, scalaJarsDirectory) =>

		val libraryPackages = getScalaLibraryPackages(scalaLibraryFile, scalaVersion)
		val libraryPath = scalaLibraryFile.getAbsolutePath
		
		for (scalaPackage <- libraryPackages) {
			if (scalaPackage.equals("scala")) {
				val jarName = "scala-root-" + scalaVersion + ".jar"
				val jarFile = new JFile(scalaJarsDirectory, jarName)
				
				if (jarFile.exists && isUpToDate(Seq(scalaLibraryFile), jarFile)) {
					streams.log.info(jarFile.getAbsolutePath + " already exists and it is up to date.")
				} else {
					repackJarRoot(libraryPath, jarFile.getAbsolutePath, scalaPackage)
					streams.log.info(scalaLibraryFile.getAbsolutePath + " => [SPLIT] => " + jarFile.getAbsolutePath)
				}
			} else if (scalaPackage.equals("scala/collection/immutable") || scalaPackage.equals("scala/collection/mutable")) {
				val jarName = "scala-" + scalaPackage.split("/")(1) + "-" + scalaPackage.split("/")(2) +"-" + scalaVersion + ".jar"
				val jarFile = new JFile(scalaJarsDirectory, jarName)
				
				if (jarFile.exists && isUpToDate(Seq(scalaLibraryFile), jarFile)) {
					streams.log.info(jarFile.getAbsolutePath + " already exists and it is up to date.")
				} else {
					repackJarPart(libraryPath, jarFile.getAbsolutePath, scalaPackage)
					streams.log.info(scalaLibraryFile.getAbsolutePath + " => [SPLIT] => " + jarFile.getAbsolutePath)
				}
			} else {
				val jarName = "scala-" + scalaPackage.split("/")(1) + "-" + scalaVersion + ".jar"
				val jarFile = new JFile(scalaJarsDirectory, jarName)
				
				if (jarFile.exists && isUpToDate(Seq(scalaLibraryFile), jarFile)) {
					streams.log.info(jarFile.getAbsolutePath + " already exists and it is up to date.")
				} else {
					repackJarCollection(libraryPath, jarFile.getAbsolutePath, scalaPackage)
					streams.log.info(scalaLibraryFile.getAbsolutePath + " => [SPLIT] => " + jarFile.getAbsolutePath)
				}
			}
		}
	}
	
	/**
	 * Creates a set of dex files which are compiled parts of scala library for Dalvik Virtual Machine.<br>
	 * Each dex file corresponds to the jar file of the split scala library of the same name.<br>
	 * <br>
	 * If a dex file exists and it is up to date with the corresponding jar file in <code>/jars</code> directory, the new dex file is NOT generated.
	 */
	private def dexScalaLibrary = (dxPath, dxOpts, proguardOptimizations, scalaVersion, streams, scalaJarsDirectory, scalaDexsDirectory) map {
	                              (dxPath, dxOpts, proguardOptimizations, scalaVersion, streams, scalaJarsDirectory, scalaDexsDirectory) =>

		/**
		 * Performs dexing on the input files, storing the result in the output file.
		 * Dexing is not executed if all the input files are up to date in regatd to the output file.
		 *
		 * @param inputs the specified input files to be dexed
		 * @param output the specified output location for writing the <code>.dex</code> file
		 */
		def dexing(inputs: Seq[JFile], output: JFile) = {
			val upToDate = isUpToDate(inputs, output)

			if (!upToDate) {
				val noLocals =
					if (proguardOptimizations.isEmpty) ""
					else "--no-locals"

				val dxCmd = (
					Seq(dxPath.absolutePath,
						dxMemoryParameter(dxOpts._1),
						"--dex", noLocals,
						"--num-threads="+java.lang.Runtime.getRuntime.availableProcessors,
						"--output="+output.getAbsolutePath
					) ++
					inputs.map(_.absolutePath)
				).filter(_.length > 0)

				streams.log.debug(dxCmd.mkString(" "))
				streams.log.debug(dxCmd !!)
			} else {
				streams.log.debug("Dex file " + output.getAbsolutePath + " up to date, skipping...")
			}
		}
		
		for (jarFile <- scalaJarsDirectory.listFiles) {
			if (jarFile.getName.endsWith(".jar")) {
				val dexFile = new JFile(scalaDexsDirectory, jarFile.getName.replace(".jar", ".dex"))
				
				if (dexFile.exists && isUpToDate(Seq(jarFile), dexFile)) {
					streams.log.info(dexFile.getAbsolutePath + " already exists and it is up to date.")
				} else {
					dexing(Seq(jarFile), dexFile)
					streams.log.info(jarFile.getAbsolutePath + " => [DEXER] => " + dexFile.getAbsolutePath)
				}
			}
		}
	}
	
	/**
	 * Creates a set of jar files which contain corresponding dexed parts of the scala library.<br>
	 * Each repacked jar file contains corresponding dex file renamed to classes.dex.<br>
	 * <br>
	 * If a repacked library jar file exists and it is up to date with the corresponding dex file in <code>/dexs</code> directory, the new library jar file is NOT generated.
	 */
	private def repackScalaLibrary = (scalaVersion, streams, scalaDexsDirectory, scalaLibsDirectory) map {
	                                 (scalaVersion, streams, scalaDexsDirectory, scalaLibsDirectory) =>

		/**
		 * Repacks dex file into a jar file.<br>
		 * The created jar file will have the same name as the specified dex file, but the dex file inside jar file will be renamed to <code>classes.dex</code><br>
		 *
		 * @param dexFile the dex file to be packed in jar
		 * @param libFile the jar file where the dex file will be packed into an android library
		 */
		def repackDexIntoJar(dexFile: JFile, libFile: JFile) = {			
			val classesDexFile = new JFile(dexFile.getAbsolutePath.replace(dexFile.getName, "classes.dex"))
			
			val copyIn = new FileInputStream(dexFile)
			val copyOut = new FileOutputStream(classesDexFile)
			
			val buffer = new Array[Byte](4096)
			Iterator.continually(copyIn.read(buffer))
					.takeWhile(_ != -1)
					.foreach { copyOut.write(buffer, 0, _) }
			copyIn.close
			copyOut.close
			
			val unzipIn = new FileInputStream(classesDexFile)
			val zipOut = new ZipOutputStream(new FileOutputStream(libFile))
			zipOut.putNextEntry(new ZipEntry(classesDexFile.getName))
			Iterator.continually(unzipIn.read(buffer))
					.takeWhile(_ != -1)
					.foreach { zipOut.write(buffer, 0, _) }
			zipOut.closeEntry
			unzipIn.close
			zipOut.close
			
			IO.delete(classesDexFile)
		}
		
		for (dexFile <- scalaDexsDirectory.listFiles) {
			if (dexFile.getName.endsWith(".dex")) {
				val libFile = new JFile(scalaLibsDirectory, dexFile.getName.replace(".dex", ".jar"))
				
				if (libFile.exists && isUpToDate(Seq(dexFile), libFile)) {
					streams.log.info(libFile.getAbsolutePath + " already exists and it is up to date.")
				} else {
					repackDexIntoJar(dexFile, libFile)
					streams.log.info(dexFile.getAbsolutePath + " => [REPACK] => " + libFile.getAbsolutePath)
				}
			}
		}
	}
	
	/**
	 * Creates a set of permissions for each part of the scala library to be pushed on the rooted device.<br>
	 * <br>
	 * Each permission is an XML file containing the name of the library part in the following format:<br>
	 * <code>scala-{PACKAGE}-{VERSION}</code> pointing to scala library part located at 
	 * <code>/system/framework/scala-{PACKAGE}-{VERSION}.jar</code><br>
	 * <br>
	 * If an permission file exists and it is up to date with the corresponding jar file in <code>/libs</code> directory, the new XML permission file is NOT generated.
	 */
	private def allowScalaLibrary = (scalaVersion, streams, scalaLibsDirectory, scalaXmlsDirectory) map {
	                                (scalaVersion, streams, scalaLibsDirectory, scalaXmlsDirectory) =>

		/**
		 * Writes an xml permission for the dex file to the xml file relative to the android project package.
		 *
		 * @param libFile the specified library file
		 * @param xmlFile the file with the permissions related to the libFile
		 */
		def permission(libFile: JFile, xmlFile: JFile) = {			
			val writer = new JPrintWriter(xmlFile)
			
			writer.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
			writer.println("")
			writer.println("<permissions>")
			writer.println("    <library")
			writer.println("        name=\"" + libFile.getName.replace(".jar", "") + "\"")
			writer.println("        file=\"/system/framework/" + libFile.getName + "\"")
			writer.println("    />")
			writer.println("</permissions>")
			
			writer.close
		}
		
		for (libFile <- scalaLibsDirectory.listFiles) {
			if (libFile.getName.endsWith(".jar")) {
				val xmlFile = new JFile(scalaXmlsDirectory, libFile.getName.replace(".jar", ".xml"))
				
				if (xmlFile.exists && isUpToDate(Seq(libFile), xmlFile)) {
					streams.log.info(xmlFile.getAbsolutePath + " already exists and it is up to date.")
				} else {
					permission(libFile, xmlFile)
					streams.log.info(libFile.getAbsolutePath + " => [PERMISSION] => " + xmlFile.getAbsolutePath)
				}
			}
		}
	}
	
	/**
	 * Mappings of new Task Keys related to checking, pushing and removing Scala library on a rooted phone.<br>
	 * <br>
	 * <code>rootScalaVersion</code> simply calls <code>checkScala</code><br>
	 * <code>rootScalaUninstall</code> calls <code>removeScala</code> but depends on <code>rootScalaVersion</code><br>
	 * <code>rootScalaInstall</code> calls <code>pushScala</code> but depends on <code>rootScalaVersion</code><br>
	 */
	lazy val rootScalaSettings: Seq[Setting[_]] = inConfig(Android) (
		Seq(			
			mainPackage <<= getPackageName,
			homeDirectory <<= getHomeDirectory,
			scalaFolder <<= getScalaFolder dependsOn homeDirectory,
			scalaLibraryFile <<= getScalaLibraryFile dependsOn scalaFolder,
			scalaJarsDirectory <<= getScalaJarsDirectory dependsOn scalaFolder,
			scalaDexsDirectory <<= getScalaDexsDirectory dependsOn scalaFolder,
			scalaLibsDirectory <<= getScalaLibsDirectory dependsOn scalaFolder,
			scalaXmlsDirectory <<= getScalaXmlsDirectory dependsOn scalaFolder,
			
			rootScalaCreateDirectories <<= createScalaDirs dependsOn (scalaJarsDirectory, scalaDexsDirectory, scalaLibsDirectory, scalaXmlsDirectory),
			rootScalaLibrarySplit <<= splitScalaLibrary dependsOn (scalaLibraryFile, rootScalaCreateDirectories),
			rootScalaLibraryDex <<= dexScalaLibrary dependsOn rootScalaLibrarySplit,
			rootScalaLibraryRepack <<= repackScalaLibrary dependsOn rootScalaLibraryDex,
			rootScalaLibraryAllow <<= allowScalaLibrary dependsOn rootScalaLibraryRepack,
			
			rootScalaVersion <<= checkScala,
			rootScalaUninstall <<= removeScala dependsOn rootScalaVersion,
			rootScalaInstall <<= pushScala dependsOn (rootScalaVersion, rootScalaLibraryAllow)
		)
	)
}