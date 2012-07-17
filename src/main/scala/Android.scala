import sbt._
import Keys._

import AndroidKeys._
import AndroidHelpers.isWindows

import complete.DefaultParsers._

object AndroidProject extends Plugin {

  // GSoC - clone dev
  val emulatorStart = InputKey[Unit]("emulator-start",
    "Launches a user specified avd")
  // GSoC - clone dev
  val emulatorStop = TaskKey[Unit]("emulator-stop",
    "Kills the running emulator.")
  val listDevices = TaskKey[Unit]("list-devices",
    "List devices from the adb server.")
  val killAdb = TaskKey[Unit]("kill-server",
    "Kill the adb server if it is running.")

  // GSoC - clone dev
  private def emulatorStartTask = (parsedTask: TaskKey[String]) =>
    (parsedTask, toolsPath) map { (avd, toolsPath) =>
      "%s/emulator -avd %s".format(toolsPath, avd).run
      ()
    }

  private def listDevicesTask: Project.Initialize[Task[Unit]] = (dbPath) map {
    _ +" devices" !
  }

  private def killAdbTask: Project.Initialize[Task[Unit]] = (dbPath) map {
    _ +" kill-server" !
  }

  // GSoC - clone dev
  private def emulatorStopTask = (dbPath, streams) map { (dbPath, s) =>
    s.log.info("Stopping emulators")
    val serial = "%s -e get-serialno".format(dbPath).!!
    "%s -s %s emu kill".format(dbPath, serial).!
    ()
  }

  def installedAvds(sdkHome: File) = (s: State) => {
    val avds = ((Path.userHome / ".android" / "avd" * "*.ini") +++
      (if (isWindows) (sdkHome / ".android" / "avd" * "*.ini")
       else PathFinder.empty)).get
    Space ~> avds.map(f => token(f.base))
                 .reduceLeftOption(_ | _).getOrElse(token("none"))
  }

  // GSoC - Je li ovdje problem?
  lazy val androidSettings: Seq[Setting[_]] =
    AndroidBase.settings ++
    AndroidLaunch.settings ++
    AndroidDdm.settings ++
	AndroidLaunch.devSettings

  // Android path and defaults can load for every project
  // No aggregation of the emulator runnables
  override lazy val settings: Seq[Setting[_]] =
    AndroidPath.settings ++ inConfig(Android) (Seq (
      listDevices <<= listDevicesTask,
      killAdb <<= killAdbTask,
	  // GSoC - clone dev
      emulatorStart <<= InputTask((sdkPath)(installedAvds(_)))(emulatorStartTask),
	  // GSoC - clone dev
      emulatorStop <<= emulatorStopTask
    )) ++ Seq (
      listDevices <<= (listDevices in Android)
    ) ++ Seq (
      listDevices,
      listDevices in Android,
	  // GSoC - clone dev
      emulatorStart in Android,
	  // GSoC - clone dev
      emulatorStop in Android
    ).map {
      aggregate in _ := false
    }
}
