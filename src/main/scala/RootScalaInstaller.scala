import sbt._
import Keys._
import AndroidKeys._
import AndroidHelpers._

import java.io.{ File => JFile }
import java.io.{ Writer => JWriter, PrintWriter => JPrintWriter }
import java.lang.{ System => JSystem }

/**
 * Provides functionality for pushing and removing different Scala versions to and from a rooted device.
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
		val home = JSystem.getProperty("user.home")
		val scalaFolder = new JFile(home + "/.sbt/boot/scala-" + version)

		if (!scalaFolder.exists) {
			println("[Root Scala Installer] `" + scalaFolder.getAbsolutePath + "` does not exist!")
			// TODO: Try to download Scala `version`
		}

		if (!scalaFolder.exists) {
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
			writer.println("        file=\"/system/framework/scala-library-" + version + "\"")
			writer.println("    />")
			writer.println("</permissions>")
			
			writer.close
		}
		
		return true
	}
	
	/**
	 * Checks if the connected device is rooted.
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
	 * Checks whether the device is rooted by examining if a <code>write</code> permission exists on root(<code>/</code>).
	 *
	 * @return <code>true</code> if a <code>write</code> permission exists on root(<code>/</code>). <code>false</code> otherwise
	 */
	private def checkRootMethodWithWritePermission(dbPath: String, streams: TaskStreams): Boolean = {
		
		val (exit, response) = adbTaskWithOutput(dbPath, false, streams,
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
	 * Attempts to push the project-specified Scala version to the connected device. The device needs to be rooted for this operation to succeed.
	 */
	def pushScala() = (dbPath, streams, scalaVersion, rootScalaVersion) map {
		              (dbPath, streams, version, installedVersions) =>
		
		val isRooted = checkRoot(dbPath, streams)
		val home = JSystem.getProperty("user.home")
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
								"/system/framework/scala-library-" + installedVersion + ".jar"
							)
							
							adbTask(
								dbPath.getAbsolutePath, false, streams, 
								"shell",
								"rm -f",
								"/system/etc/permissions/scala-library-" + installedVersion +  ".xml"
							)
						}
					}
					
					if (!installedVersions.contains(version)) {
						streams.log.info("[Development] Pushing Scala library version: " + version + "...")
						
						adbTask(
							dbPath.getAbsolutePath, false, streams, 
							"push", 
							home + sep + ".sbt" + sep + "boot" + sep + "scala-" + version + sep + "lib" + sep + "scala-library.jar", 
							"/system/framework/scala-library-" + version + ".jar"
						)
						
						adbTask(
							dbPath.getAbsolutePath, false, streams, 
							"push", 
							home + sep + ".sbt" + sep + "boot" + sep + "scala-" + version + sep + "scala-library.xml", 
							"/system/etc/permissions/scala-library-" + version +  ".xml"
						)
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
	 * Attempts to remove all the Scala versions from the connected device. The device needs to be rooted for this operation to succeed.
	 */
	def removeScala() = (dbPath, streams, rootScalaVersion) map {
	                    (dbPath, streams, installedVersions) =>
		
		val isRooted = checkRoot(dbPath, streams)
		
		if (isRooted && !installedVersions.isEmpty) {
			streams.log.info("[Development] Rooted device detected. Attempting to remove Scala libraries from device: " + installedVersions.mkString(", ") + "...")
			
			streams.log.info("[Development] Remounting device...")
			adbTask(dbPath.getAbsolutePath, false, streams, "remount")
			
			for (installedVersion <- installedVersions) {
				streams.log.info("[Development] Removing Scala library version: " + installedVersion + "...")
				
				adbTask(
					dbPath.getAbsolutePath, false, streams, 
					"shell",
					"rm -f",
					"/system/framework/scala-library-" + installedVersion + ".jar"
				)
				
				adbTask(
					dbPath.getAbsolutePath, false, streams, 
					"shell",
					"rm -f",
					"/system/etc/permissions/scala-library-" + installedVersion +  ".xml"
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
	 * Checks if there is already a Scala library on the rooted device and saves that as a setting.
	 */
	def checkScala(): Project.Initialize[Task[Seq[String]]] = (dbPath, streams) map {
	                                                                  (dbPath, streams) =>
		
		val isRooted = checkRoot(dbPath, streams)
		
		if (isRooted) {
			streams.log.info("[Development] Rooted device detected. Checking Scala version on device...")
			
			val (exit, response) = adbTaskWithOutput(
				dbPath.getAbsolutePath, false, streams, 
				"shell",
				"ls /system/framework/scala-library*.jar"
			)
			
			if ((exit != 0) || (response.contains("Failure"))) {
				streams.log.info("[Development] Error executing ADB.")
				Seq.empty[String]
			} else if (response.contains("No such file") || !response.contains("/system/framework/scala-library-")) {
				streams.log.info("[Development] No Scala library detected on device")
				Seq.empty[String]
			} else {
				var versions = Seq.empty[String]
				val lines = response.split("\n")
				
				for (line <- lines) {
					val v = line.split("/system/framework/scala-library-")(1).split("\\.jar")(0)
					versions = v +: versions 
					streams.log.info("[Development] Found Scala Library version: " + v)
				}
				
				versions
			}

		} else {
			streams.log.info("[Development] Your device is not rooted. This operations is supported by rooted phones only.")
			Seq.empty[String]
		}
		
	}
	
	/**
	 * The mappings of various Scala versions and their TaskKeyss
	 */
	lazy val rootScalaSettings: Seq[Setting[_]] = inConfig(Android) (
		Seq(
			rootScalaVersion <<= checkScala,
			rootScalaUninstall <<= removeScala dependsOn rootScalaVersion,
			rootScalaInstall <<= pushScala dependsOn rootScalaVersion
		)
	)
}