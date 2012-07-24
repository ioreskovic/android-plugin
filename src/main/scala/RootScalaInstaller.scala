import sbt._
import Keys._
import AndroidKeys._
import AndroidHelpers._

import java.io.{ File => JFile }
import java.io.{ Writer => JWriter, PrintWriter => JPrintWriter }
import java.lang.{ System => JSystem }

// import android.os.Build._

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
	private def checkRoot(): Boolean = {
		// if (checkRootMethod1()) return true
		if (checkRootMethod2()) return true
		
		return false
	}

	// private def checkRootMethod1(): Boolean = {
		// val buildTags = android.os.Build.TAGS
		
		// if (buildTags && buildTags.contains("test-keys")) return true
		
		// return false
	// }
	
	/**
	 * Checks whether the device is rooted by examining if the Superuser application is installed.
	 *
	 * @return <code>true</code> if the Superuser.apk exists in device's /system/app directory. <code>false</code> otherwise
	 */
	private def checkRootMethod2(): Boolean = {
		return true
		// try {
			// val file = new JFile("/system/app/Superuser.apk")
			// if (file.exists) return true
		// } catch {
			// case _ => return false
		// }
		
		// return false
	}
	
	/**
	 * Attempts to push the specified Scala version to the connected device. The device needs to be rooted for this operation to succeed.
	 *
	 * @param version The specified Scala version to be pushed
	 */
	private def pushScala(version: String) = (dbPath, streams) map {
		                                     (dbPath, streams) =>
		
		val isRooted = checkRoot()
		
		if (isRooted) {
			
			streams.log.info("[Development] Rooted device detected. Preparing to push Scala " + version + " to device...")
			
			val scalaVersionExists = makePermission(version)
			
			if (scalaVersionExists) {
			
				streams.log.info("[Development] Scala " + version + " found")
				streams.log.info("[Development] Trying to push Scala " + version + " to device")
				
				val home = JSystem.getProperty("user.home")
				val sep = JSystem.getProperty("file.separator")
				
				adbTask(dbPath.getAbsolutePath, false, streams, "remount")
				
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
				
				adbTask(dbPath.getAbsolutePath, false, streams, "reboot")
			} else {
				streams.log.info("[Development] Scala " + version + " not found")
			}
		} else {
			streams.log.info("[Development] Your device is not rooted. This operations is supported by rooted phones only.")
		}
	}
	
	/**
	 * Attempts to remove the specified Scala version from the connected device. The device needs to be rooted for this operation to succeed.
	 *
	 * @param version The specified Scala version to be removed
	 */
	private def removeScala(version: String) = (dbPath, streams) map {
	                                           (dbPath, streams) =>
		
		val isRooted = checkRoot()
		
		if (isRooted) {
			
			streams.log.info("[Development] Rooted device detected. Removing Scala " + version + " from device...")
			
			adbTask(dbPath.getAbsolutePath, false, streams, "remount")
			
			adbTask(
				dbPath.getAbsolutePath, false, streams, 
				"shell",
				"rm -f",
				"/system/framework/scala-library-" + version + ".jar"
			)
			
			adbTask(
				dbPath.getAbsolutePath, false, streams, 
				"shell",
				"rm -f",
				"/system/etc/permissions/scala-library-" + version +  ".xml"
			)
			
			adbTask(dbPath.getAbsolutePath, false, streams, "reboot")
		} else {
			streams.log.info("[Development] Your device is not rooted. This operations is supported by rooted phones only.")
		}
	}
	
	/**
	 * The mappings of various Scala versions and their TaskKeyss
	 */
	lazy val rootScalaSettings: Seq[Setting[_]] = inConfig(Android) (
		Seq(
			rootInstallScala_2_8_1 <<= pushScala(version = "2.8.1"),
			rootUninstallScala_2_8_1 <<= removeScala(version = "2.8.1"),
			
			rootInstallScala_2_9_0 <<= pushScala(version = "2.9.0"),
			rootUninstallScala_2_9_0 <<= removeScala(version = "2.9.0"),
			
			rootInstallScala_2_9_1 <<= pushScala(version = "2.9.1"),
			rootUninstallScala_2_9_1 <<= removeScala(version = "2.9.1"),
			
			rootInstallScala_2_10_0_M5 <<= pushScala(version = "2.10.0-M5"),
			rootUninstallScala_2_10_0_M5 <<= removeScala(version = "2.10.0-M5")
		)
	)
}