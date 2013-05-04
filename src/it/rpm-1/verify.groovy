def rpm = new File(localRepositoryPath, "org/codehaus/mojo/rpm/its/rpm-1/1.0/rpm-1-1.0.rpm")
if (!rpm.exists())
    throw new AssertionError("RPM artifact not created: ${rpm.getAbsolutePath()}")

File filteredFile = new File(basedir, "target/rpm/rpm-1/buildroot/usr/myusr/app/bin/filter.txt");
if (!filteredFile.exists())
    throw new AssertionError("/usr/myusr/app/bin/filter.txt does not exist")
if (!"org.codehaus.mojo.rpm.its".equals(filteredFile.readLines()[0]))
    throw new AssertionError("contents of filter.txt expected[org.codehaus.mojo.rpm.its] actual[${filteredFile.readLines()[0]}]");

File filteredVersionFile = new File(basedir, "target/rpm/rpm-1/buildroot/usr/myusr/app/bin/filter-version.txt")
if (!filteredVersionFile.exists())
    throw new AssertionError("/usr/myusr/app/bin/filter-version.txt does not exist");
if (!"1.0-1".equals(filteredVersionFile.readLines()[0]))
    throw new AssertionError("contents of filter-version.txt expected[1.0-1] actual[${filteredVersionFile.readLines()[0]}]");

lines = new File(basedir, "target/rpm/rpm-1/SPECS/rpm-1.spec").readLines()
[
        "Name: rpm-1",
        "Version: 1.0",
        "Release: 1",
        "License: 2009 my org"
].each {
    if (!lines.contains(it))
        throw new AssertionError("Spec file missing \"${it}\"")
}

proc = ["rpm", "-qvlp", rpm.getAbsolutePath()].execute()
proc.waitFor()
lines = proc.in.text.readLines()

if (lines.size() != 15)
    throw new AssertionError("Incorrect file count: 15 != ${lines.size()}")

[
        /-rwxr-xr-x\s.*\smyuser\s+mygroup\s.*\s\/usr\/myusr\/app\/bin\/filter-version.txt/,
        /-rwxr-xr-x\s.*\smyuser\s+mygroup\s.*\s\/usr\/myusr\/app\/bin\/filter.txt/,
        /-rwxr-xr-x\s.*\smyuser\s+mygroup\s.*\s\/usr\/myusr\/app\/bin\/name-${System.getProperty("os.name")}.sh/,
        /-rwxr-xr-x\s.*\smyuser\s+mygroup\s.*\s\/usr\/myusr\/app\/bin\/name.sh/,
        /lrwxr-xr-x\s.*\smyuser\s+mygroup\s.*\s\/usr\/myusr\/app\/bin\/oldname.sh -> name.sh/,
        /-rwxr-xr-x\s.*\smyuser\s+mygroup\s.*\s\/usr\/myusr\/app\/bin\/start.sh/,
        /l.*\s\/tmp\/myapp\/somefile2 -> \/tmp\/myapp\/somefile/,
        /-.*\s\/usr\/myusr\/app\/lib\/grizzly-comet-counter.war/
].each {
    if (!lines*.matches(it).contains(true))
        throw new AssertionError("File/dir/link matching ${it.toString()} missing from RPM!")
}

return true

