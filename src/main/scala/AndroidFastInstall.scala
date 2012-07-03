import scala.collection.mutable.ListBuffer

import proguard.{Configuration=>ProGuardConfiguration, ProGuard, ConfigurationParser}

import sbt._
import Keys._
import AndroidKeys._
import AndroidHelpers._

import java.io.{File => JFile}

// GSoC 
// Used in merging .dex files
import com.android.dx.io.DexBuffer
import com.android.dx.merge.DexMerger
import com.android.dx.merge.CollisionPolicy

object AndroidFastInstall {
  
  // GSoC
  private def devInstallTask(emulator: Boolean) = (dbPath, packageApkPath, streams) map { (dp, p, s) =>
	adbTask(dp.absolutePath, emulator, s, "install", "-r ", p.absolutePath)
  }

  private def uninstallTask(emulator: Boolean) = (dbPath, manifestPackage, streams) map { (dp, m, s) =>
    adbTask(dp.absolutePath, emulator, s, "uninstall", m)
  }

  private def aaptPackageTask: Project.Initialize[Task[File]] =
  (aaptPath, manifestPath, mainResPath, mainAssetsPath, jarPath, resourcesApkPath, extractApkLibDependencies, streams) map {
    (apPath, manPath, rPath, assetPath, jPath, resApkPath, apklibs, s) =>

	// GSoC - NE DIRAJ
    val libraryResPathArgs = for (
      lib <- apklibs;
      d <- lib.resDir.toSeq;
      arg <- Seq("-S", d.absolutePath)
    ) yield arg

	//	GSoC - NE DIRAJ
    val aapt = Seq(apPath.absolutePath, "package", "--auto-add-overlay", "-f",
        "-M", manPath.head.absolutePath,
        "-S", rPath.absolutePath,
        "-A", assetPath.absolutePath,
        "-I", jPath.absolutePath,
        "-F", resApkPath.absolutePath) ++
        libraryResPathArgs
		
    s.log.debug("packaging: "+aapt.mkString(" "))
    if (aapt.run(false).exitValue != 0) sys.error("error packaging resources")
    resApkPath
  }

  /**
  * Google Summer of Code
  
  * Checks whether the input files are up to date in regard to the output file.
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
  * Google Summer of Code
  *
  * Merges all the input dex files into the output file
  */
  def mergeDex(inputs: Seq[JFile], output: JFile) {
	println("(" + inputs + ") => [DEX MERGER] => (" + output + ")")
	val lb = new ListBuffer[DexBuffer]
	
	for (file <- inputs) {
	  if (file.isFile && file.canRead && file.getName.endsWith(".dex")) {
		lb += new DexBuffer(file)
	  }
	}
	
	val dexFiles = lb.toList
	
	if (dexFiles.length <= 0) {
	  return
	} else if (dexFiles.length == 1) {
	  val dexThis = dexFiles.head
	  dexThis.writeTo(output)
	  return
	} else {
	  var dexThis = dexFiles.head
	  val others = dexFiles.tail
	  
	  for (dexOther <- others) {
		val dexMerger = new DexMerger(dexThis, dexOther, CollisionPolicy.FAIL)
		dexThis = dexMerger.merge
	  }
	  
	  dexThis.writeTo(output)
	  return
	}
	
  }
  
  private def dxTask: Project.Initialize[Task[File]] =
    (dxPath, dxInputs, dxOpts, proguardOptimizations, classDirectory, classesDexPath, scalaInstance, streams) map {
    (dxPath, dxInputs, dxOpts, proguardOptimizations, classDirectory, classesDexPath, scalaInstance, streams) =>
	
	  //------------------------------------------------------------------------\\
	  //        Modified Google Summer of Code Android Plugin functions         \\
	  //------------------------------------------------------------------------\\
	  
	  
	  /**
	  * Google Summer of Code
	  
	  * Performs dexing on the input files, storing the result in the output file.
	  * Dexing is not executed if all the input files are up to date in regatd to the output file.
	  */
	  def dexing(inputs: Seq[JFile], output: JFile) {
	    val upToDate = isUpToDate(inputs, output)
		
		if (!upToDate) {
		  val noLocals = if (proguardOptimizations.isEmpty) ""
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
		  streams.log.info("[GSoC] Dexing "+output.getAbsolutePath)
		  streams.log.debug(dxCmd !!)
		} else {
		  streams.log.debug("dex file " + output.getAbsolutePath + " uptodate, skipping")
		}
	  }
	  
	  
	  dexing(dxInputs.get, classesDexPath)
	  mergeDex(Seq(classesDexPath), classesDexPath)
	  
	  // Sto se tocno nalazi u dxOpts?
	  
      // Option[Seq[String]]
      // - None standard dexing for prodaction stage
      // - Some(Seq(predex_library_regexp)) predex only changed libraries for development stage
	  
	  //streams.log.info(dxOpts)
	  
      // dxOpts._2 match {
        // case None =>
          // dexing(dxInputs.get, classesDexPath)
        // case Some(predex) =>
          // val (dexFiles, predexFiles) = predex match {
            // case exceptSeq: Seq[_] if exceptSeq.nonEmpty =>
              // val (filtered, orig) = dxInputs.get.partition(file =>
              // exceptSeq.exists(filter => {
                // streams.log.debug("apply filter \"" + filter + "\" to \"" + file.getAbsolutePath + "\"")
                // file.getAbsolutePath.matches(filter)
              // }))
              // // dex only classes directory ++ filtered, predex all other
              // ((classDirectory --- scalaInstance.libraryJar).get ++ filtered, orig)
            // case _ =>
              // // dex only classes directory, predex all other
              // ((classDirectory --- scalaInstance.libraryJar).get, (dxInputs --- classDirectory).get)
          // }
          // dexFiles.foreach(s => streams.log.debug("pack in dex \"" + s.getName + "\""))
          // predexFiles.foreach(s => streams.log.debug("pack in predex \"" + s.getName + "\""))
          // // dex
          // dexing(dexFiles, classesDexPath)
          // // predex
          // predexFiles.get.foreach(f => {
            // val predexPath = new JFile(classesDexPath.getParent, "predex")
            // if (!predexPath.exists)
              // predexPath.mkdir
            // val output = new File(predexPath, f.getName)
            // val outputPermissionDescriptor = new File(predexPath, f.getName.replaceFirst(".jar$", ".xml"))
            // dexing(Seq(f), output)
            // val permission = <permissions><library name={ f.getName.replaceFirst(".jar$", "") } file={ "/data/" + f.getName } /></permissions>
            // val p = new java.io.PrintWriter(outputPermissionDescriptor)
            // try { p.println(permission) } finally { p.close() }
          // })
      // }

      classesDexPath
    }
	
  /**
  * Google Summer of Code
  *
  * Runs ProGuard on all inputs, storing the result to the output file.
  */
  private def proguardTask: Project.Initialize[Task[Option[File]]] = 
	(useProguard, proguardOptimizations, classDirectory, proguardInJars, streams, androidAppClassesMinJarPath, libraryJarPath, manifestPackage, proguardOption) map { (useProguard, proguardOptimizations, classDirectory, proguardInJars, streams, androidAppClassesMinJarPath, libraryJarPath, manifestPackage, proguardOption) =>
	  if (useProguard) {
		val optimizationOptions = if (proguardOptimizations.isEmpty) Seq("-dontoptimize") else proguardOptimizations
		val manifestr = List("!META-INF/MANIFEST.MF", "R.class", "R$*.class", "TR.class", "TR$.class", "library.properties")
		val sep = JFile.pathSeparator
		val inJars = ("\"" + classDirectory.absolutePath + "\"") +: proguardInJars.map("\"" + _ + "\""+manifestr.mkString("(", ",!**/", ")"))

		val args = (
			"-injars" :: inJars.mkString(sep) ::
			"-outjars" :: "\""+androidAppClassesMinJarPath.absolutePath+"\"" ::
			"-libraryjars" :: libraryJarPath.map("\""+_+"\"").mkString(sep) ::
			Nil) ++
			optimizationOptions ++ (
			"-dontwarn" :: "-dontobfuscate" ::
			"-dontnote scala.Enumeration" ::
			"-dontnote org.xml.sax.EntityResolver" ::
			"-keep public class * extends android.app.Activity" ::
			"-keep public class * extends android.app.Service" ::
			"-keep public class * extends android.app.backup.BackupAgent" ::
			"-keep public class * extends android.appwidget.AppWidgetProvider" ::
			"-keep public class * extends android.content.BroadcastReceiver" ::
			"-keep public class * extends android.content.ContentProvider" ::
			"-keep public class * extends android.view.View" ::
			"-keep public class * extends android.app.Application" ::
			"-keep public class "+manifestPackage+".** { public protected *; }" ::
			"-keep public class * implements junit.framework.Test { public void test*(); }" ::
			"""
			 -keepclassmembers class * implements java.io.Serializable {
			   private static final java.io.ObjectStreamField[] serialPersistentFields;
			   private void writeObject(java.io.ObjectOutputStream);
			   private void readObject(java.io.ObjectInputStream);
			   java.lang.Object writeReplace();
			   java.lang.Object readResolve();
			  }
			  """ ::
			proguardOption :: Nil )
			
		val config = new ProGuardConfiguration
		new ConfigurationParser(args.toArray[String]).parse(config)
		streams.log.debug("executing proguard: "+args.mkString("\n"))
		new ProGuard(config).execute
		Some(androidAppClassesMinJarPath)
	} else {
		streams.log.info("Skipping Proguard")
		None
	}
  }

  // GSoC
  // Mislim da ne treba nista mijenjati
  private def packageTask(debug: Boolean):Project.Initialize[Task[File]] = (packageConfig, streams) map { (c, s) =>
    val builder = new ApkBuilder(c, debug)
    builder.build.fold(sys.error(_), s.log.info(_))
    s.log.debug(builder.outputStream.toString)
    c.packageApkPath
  }
  
  // GSoC - DONE
  // Replace installEmulator and installDevice with devInstallEmulator and devInstallDevice
  lazy val installerTasks = Seq (
    devInstallDevice <<= devInstallTask(emulator = false) dependsOn packageDebug,
	devInstallEmulator <<= devInstallTask(emulator = true) dependsOn packageDebug
  )

  // GSoC
  // Tu mozda nesto?
  lazy val settings: Seq[Setting[_]] = inConfig(Android) (installerTasks ++ Seq (
    uninstallEmulator <<= uninstallTask(emulator = true),
    uninstallDevice <<= uninstallTask(emulator = false),

    makeAssetPath <<= directory(mainAssetsPath),

    aaptPackage <<= aaptPackageTask,
    aaptPackage <<= aaptPackage dependsOn (makeAssetPath, dx),
    dx <<= dxTask,
    dxInputs <<= (proguard, proguardInJars, scalaInstance, classDirectory) map {
      (proguard, proguardInJars, scalaInstance, classDirectory) =>
      proguard match {
         case Some(file) => Seq(file)
         case None => (classDirectory +++ proguardInJars --- scalaInstance.libraryJar) get
      }
    },

    cleanApk <<= (packageApkPath) map (IO.delete(_)),

	// GSoC
	// Je li ovo problem?
    proguard <<= proguardTask,
    proguard <<= proguard dependsOn (compile in Compile),

	// GSoC
	// Mislim da odavde ide pakiranje aplikacije
    packageConfig <<=
      (toolsPath, packageApkPath, resourcesApkPath, classesDexPath,
       nativeLibrariesPath, managedNativePath, dxInputs, resourceDirectory) map
      (ApkConfig(_, _, _, _, _, _, _, _)),

    packageDebug <<= packageTask(true),
    packageRelease <<= packageTask(false)
  ) ++ Seq(packageDebug, packageRelease).map {
    t => t <<= t dependsOn (cleanApk, aaptPackage, copyNativeLibraries)
  })
}
