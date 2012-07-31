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
	
	lazy val mainPackage = getPackageName
	
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
		
		val in = new ZipInputStream(new FileInputStream(new JFile(src)))
		val out = new ZipOutputStream(new FileOutputStream(new JFile(dest)))
		
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
		
		val in = new ZipInputStream(new FileInputStream(new JFile(src)))
		val out = new ZipOutputStream(new FileOutputStream(new JFile(dest)))
		
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
		
		val in = new ZipInputStream(new FileInputStream(new JFile(src)))
		val out = new ZipOutputStream(new FileOutputStream(new JFile(dest)))
		
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
	 * Creates all the directories (as well as their hierarchical structures if they don't exist.
	 * 
	 * @param dirs the directories to create
	 */
	private def makeDirs(dirs: JFile*) {
		for (dir <- dirs) {
			if (!dir.exists) {
				dir.mkdirs
			}
		}
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
	 * Splits the scala library into multiple, smaller parts divided by root packages
	 */
	private def splitLibrary = (dxPath, dxOpts, proguardOptimizations, scalaVersion, streams) map { (dxPath, dxOpts, proguardOptimizations, version, streams) => 
	
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
				streams.log.info("[Development] Dexing "+output.getAbsolutePath)
				streams.log.debug(dxCmd !!)
			} else {
				streams.log.debug("Dex file " + output.getAbsolutePath + " up to date, skipping...")
			}
		}
	
		val home = userHome
		val scalaLibraryFile = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/scala-library.jar")
		val scalaJarsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/jars")
		val scalaDexsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/dexs")
		val scalaXmlsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/xmls")
		val scalaLibsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/libs")
		
		makeDirs(scalaJarsDir, scalaDexsDir, scalaXmlsDir, scalaLibsDir)
		
		val libraryPackages = getScalaLibraryPackages(scalaLibraryFile, version)
		
		val libraryPath = scalaLibraryFile.getAbsolutePath
		
		for (scalaPackage <- libraryPackages) {
			if (scalaPackage.equals("scala")) {
				val packagePath = new JFile(scalaJarsDir, "scala-root-" + version + ".jar").getAbsolutePath
				repackJarRoot(libraryPath, packagePath, scalaPackage)
				streams.log.info("[Development] Created " + "scala-root-" + version + ".jar")
			} else if (scalaPackage.equals("scala/collection/immutable") || scalaPackage.equals("scala/collection/mutable")) {
				val packagePath = new JFile(scalaJarsDir, "scala-" + scalaPackage.split("/")(1) + "-" + scalaPackage.split("/")(2) +"-" + version + ".jar").getAbsolutePath
				repackJarPart(libraryPath, packagePath, scalaPackage)
				streams.log.info("[Development] Created " + "scala-" + scalaPackage.split("/")(1) + "-" + scalaPackage.split("/")(2) +"-" + version + ".jar")
			} else {
				val packagePath = new JFile(scalaJarsDir, "scala-" + scalaPackage.split("/")(1) + "-" + version + ".jar").getAbsolutePath
				repackJarCollection(libraryPath, packagePath, scalaPackage)
				streams.log.info("[Development] Created " + "scala-" + scalaPackage.split("/")(1) + "-" + version + ".jar")
			}
		}
		
		for (file <- scalaJarsDir.listFiles) {
			if (file.getName.endsWith(".jar")) {
				dexing(Seq(file), new JFile(scalaDexsDir, file.getName.replace(".jar", ".dex")))
				streams.log.info("[Development] Dexed " + file.getName.replace(".jar", ".dex"))
			}
		}
		
		for (file <- scalaDexsDir.listFiles) {
			if (file.getName.endsWith(".dex")) {
				repackDexIntoJar(file, new JFile(scalaLibsDir, file.getName.replace(".dex", ".jar")), version)
				streams.log.info("[Development] Repacked " + file.getName.replace(".dex", ".jar"))
			}
		}
		
		// for (file <- scalaDexsDir.listFiles) {
		for (file <- scalaLibsDir.listFiles) {
			// if (file.getName.endsWith(".dex")) {
			if (file.getName.endsWith(".jar")) {
				permission(file, new JFile(scalaXmlsDir, file.getName.replace(".jar", ".xml")))
				streams.log.info("[Development] Allowed " + file.getName.replace(".jar", ".xml"))
			}
		}
	}
	
	private def repackDexIntoJar(dexFile: JFile, jarFile: JFile, version: String) = {
		val home = userHome
		val scalaLibsDir = new JFile(home + "/.sbt/boot/scala-" + version + "/lib/libs")
		
		val classesDexFile = new JFile(dexFile.getAbsolutePath.replace(dexFile.getName, "classes.dex"))
		val classesLibFile = new JFile(scalaLibsDir, dexFile.getName.replace(".dex", ".jar"))
		
		val copyIn = new FileInputStream(dexFile)
		val copyOut = new FileOutputStream(classesDexFile)
		
		val buffer = new Array[Byte](4096)
		Iterator.continually(copyIn.read(buffer))
				.takeWhile(_ != -1)
				.foreach { copyOut.write(buffer, 0, _) }
		copyIn.close
		copyOut.close
		
		val unzipIn = new FileInputStream(classesDexFile)
		val zipOut = new ZipOutputStream(new FileOutputStream(classesLibFile))
		zipOut.putNextEntry(new ZipEntry(classesDexFile.getName))
		Iterator.continually(unzipIn.read(buffer))
				.takeWhile(_ != -1)
				.foreach { zipOut.write(buffer, 0, _) }
		zipOut.closeEntry
		unzipIn.close
		zipOut.close
		
		IO.delete(classesDexFile)
	}
	
	/**
	 * Writes an xml permission for the dex file to the xml file relative to the android project package.
	 */
	private def permission(dexFile: JFile, xmlFile: JFile) = {
		val home = userHome
		
		if (!xmlFile.exists || !isUpToDate(Seq(dexFile), xmlFile)) {
			val writer = new JPrintWriter(xmlFile)
			
			writer.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
			writer.println("")
			writer.println("<permissions>")
			writer.println("    <library")
			writer.println("        name=\"" + dexFile.getName.replace(".jar", "") + "\"")
			// writer.println("        name=\"" + dexFile.getName.replace(".dex", "") + "\"")
			writer.println("        file=\"/data/data/" + mainPackage + "/files/" + dexFile.getName + "\"")
			writer.println("    />")
			writer.println("</permissions>")
			
			writer.close
		}
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
	 * Finds the package name where Main Activity is located in
	 *
	 * @return the package name where Main Activity is located in
	 */
	def getPackageName: String = {
		val projectFiles = recursiveListFiles(new File("."))
		val manifestFile = projectFiles
			.filter(f => f.getCanonicalPath.endsWith("AndroidManifest.xml"))
			.toList.headOption
			.getOrElse(throw new FileNotFoundException("Your project doesn't contain manifest"))

		val manifestXML = scala.xml.XML.loadFile(manifestFile)
		val pkg = manifestXML.attribute("package").getOrElse("")
		pkg.asInstanceOf[scala.xml.Text].data
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
								// "/data/data/" + mainPackage + "/files/scala-*" + installedVersion + ".dex"
								"/data/data/" + mainPackage + "/files/scala-*" + installedVersion + ".jar"
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
						
						// for (file <- scalaDexsDir.listFiles) {
						// for (file <- scalaJarsDir.listFiles) {
						for (file <- scalaLibsDir.listFiles) {
							if (file.getName.endsWith(".jar")) {
								adbTask(
									dbPath.getAbsolutePath, false, streams, 
									"push", 
									// home + sep + ".sbt" + sep + "boot" + sep + "scala-" + version + sep + "lib" + sep + "dexs" + sep + file.getName, 
									home + sep + ".sbt" + sep + "boot" + sep + "scala-" + version + sep + "lib" + sep + "libs" + sep + file.getName, 
									"/data/data/" + mainPackage + "/files/" + file.getName
								)
								
								adbTask(
									dbPath.getAbsolutePath, false, streams, 
									"shell",
									"su -c",
									"\"chmod 666 /data/data/" + mainPackage + "/files/" + file.getName + "\""
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
					"/data/data/" + mainPackage + "/files/scala-*" + installedVersion + ".jar"
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
				"ls /data/data/" + mainPackage + "/files/scala-*.jar"
			)
			
			if ((exit != 0) || (response.contains("Failure"))) {
				streams.log.info("[Development] Error executing ADB.")
				Seq.empty[String]
			} else if (response.contains("No such file") || !response.contains("/data/data/" + mainPackage + "/files/scala-")) {
				streams.log.info("[Development] No Scala library detected on device")
				Seq.empty[String]
			} else {
				var versions = Seq.empty[String]
				val lines = response.split("\n")
				
				for (line <- lines) {
					val v = line.split("/data/data/" + mainPackage + "/files/")(1).split("-").last.split(".jar")(0)
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
	 * Mappings of new Task Keys related to checking, pushing and removing Scala library on a rooted phone.<br>
	 * <br>
	 * <code>rootScalaVersion</code> simply calls <code>checkScala</code><br>
	 * <code>rootScalaUninstall</code> calls <code>removeScala</code> but depends on <code>rootScalaVersion</code><br>
	 * <code>rootScalaInstall</code> calls <code>pushScala</code> but depends on <code>rootScalaVersion</code><br>
	 */
	lazy val rootScalaSettings: Seq[Setting[_]] = inConfig(Android) (
		Seq(
			rootScalaVersion <<= checkScala,
			rootScalaUninstall <<= removeScala dependsOn rootScalaVersion,
			rootScalaInstall <<= pushScala dependsOn rootScalaVersion,
			rootScalaLibrarySplit <<= splitLibrary
		)
	)
}