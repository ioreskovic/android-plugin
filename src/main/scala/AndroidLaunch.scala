import sbt._

import Keys._
import AndroidKeys._
import AndroidHelpers._

object AndroidLaunch {

  private def startTask(emulator: Boolean) =
    (dbPath, manifestSchema, manifestPackage, manifestPath, streams) map {
      (dp, schema, mPackage, amPath, s) =>
      adbTask(dp.absolutePath,
              emulator, s,
              "shell", "am", "start", "-a", "android.intent.action.MAIN",
              "-n", mPackage+"/"+launcherActivity(schema, amPath.head, mPackage))
  }
  
  private def devStartTask(emulator: Boolean) =
    (dbPath, manifestSchema, manifestPackage, manifestPath, streams) map {
      (dp, schema, mPackage, amPath, s) =>
      adbTask(dp.absolutePath,
              emulator, s,
              "shell", "am", "start", "-a", "android.intent.action.MAIN",
              "-n", mPackage+"/"+launcherActivity(schema, amPath.head, mPackage))
  }

  private def launcherActivity(schema: String, amPath: File, mPackage: String) = {
    val launcher = for (
         activity <- (manifest(amPath) \\ "activity");
         action <- (activity \\ "action");
         val name = action.attribute(schema, "name").getOrElse(sys.error{
            "action name not defined"
          }).text;
         if name == "android.intent.action.MAIN"
    ) yield {
      val act = activity.attribute(schema, "name").getOrElse(sys.error("activity name not defined")).text
      if (act.contains(".")) act else mPackage+"."+act
    }
    launcher.headOption.getOrElse("")
  }

  /**
   * Google Summer of Code
   *
   * Iz nekog razloga kada su oba ukljucena, zadnji prekriva sve ostale
   */
  lazy val settings: Seq[Setting[_]] =
    // AndroidFastInstall.settings ++
    // inConfig(Android) (Seq (
	  // devStartDevice <<= devStartTask(false),
	  // devStartEmulator <<= devStartTask(true),
	  
	  // devStartDevice <<= devStartDevice dependsOn devInstallDevice,
	  // devStartEmulator <<= devStartEmulator dependsOn devInstallEmulator
    // ))++
    AndroidInstall.settings ++
	inConfig(Android) (Seq (
      startDevice <<= startTask(false),
      startEmulator <<= startTask(true),
	  
      startDevice <<= startDevice dependsOn installDevice,
      startEmulator <<= startEmulator dependsOn installEmulator
    ))
  
  lazy val devSettings: Seq[Setting[_]] =
    // AndroidInstall.settings ++
	// inConfig(Android) (Seq (
      // startDevice <<= startTask(false),
      // startEmulator <<= startTask(true),
	  
      // startDevice <<= startDevice dependsOn installDevice,
      // startEmulator <<= startEmulator dependsOn installEmulator
    // ))++
    AndroidFastInstall.devSettings ++
    inConfig(Android) (Seq (
	  devStartDevice <<= devStartTask(false),
	  devStartEmulator <<= devStartTask(true),
	  
	  devStartDevice <<= devStartDevice dependsOn devInstallDevice,
	  devStartEmulator <<= devStartEmulator dependsOn devInstallEmulator
    ))
}
