/*					Algorithm - Pass 1					*/
open classes.dex
go to scala package
list all the classes there
pass those classes to ProGuard to keep them whole

/*					Algorithm - Pass 2					*/
if classes.dex exists
read it
search for scala package
for each compiled class there, remember the name
run progurad with -keep option with remembered classes
else run proguard without any -keep arguments

/*					Algorithm - Pass 3					*/
if (classes.dex exists and is readable)
read classes.dex into DexContainer
recursively pass over scala package,
remembering each class there
else do nothing

run proguard with -keep option with remembered compiled classes as arguments

/*					Algorithm - Pass 4					*/
if (File(classes.dex).exists && File(classes.dex).canRead) {
	DexContainer(File(classes.dex))
	Set[String] keepClasses = listClasses(DexContainer(File(classes.dex)))	
}
runProguardTask(keepClasses)

/*					Algorithm - Pass 5					*/
import scala.collection.mutable.{HashSet => MHashSet}
import java.io.{File => JFile}
import dalvik.system.DexFile

private class RichEnumeration[T](enumeration: Enumeration[T]) extends Iterator[T] {
  def hasNext: Boolean =  enumeration.hasMoreElements()
  def next: T = enumeration.nextElement()
}

implicit def enumerationToRichEnumeration[T](enumeration: Enumeration[T]): RichEnumeration[T] = {
	new RichEnumeration(enumeration)
}

var mhs = new MHashSet[String]()
var classesDex = new JFile(classesDexPath)

if (classesDex.exists && classesDex.canRead) {

	var dexFile = new DexFile(/*fullApkPath*/)
	var classEntries = dexFile.entries()
	
	classEntries.foreach(
		className => {
			mhs += className.replaceAll("/", ".")
		}
	)

}

var keepList = mhs.map.("-keep public class " + _ + "{*;}").toList
args ++ keepList
