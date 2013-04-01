import org.codehaus.mojo.unix.rpm.RpmUtil
import org.codehaus.mojo.unix.rpm.RpmUtil.FileInfo
import org.codehaus.mojo.unix.rpm.RpmUtil.SpecFile

import java.io.*

boolean success = true

def rpm = new File(localRepositoryPath, "org/codehaus/mojo/rpm/its/rpm-ln-1/1.0/rpm-ln-1-1.0.rpm")
if (!rpm.exists())
    throw new java.lang.AssertionError("RPM artifact not created: " + rpm.getAbsolutePath());

def spec = RpmUtil.getSpecFileFromRpm(rpm)
if (!spec.name.equals("rpm-ln-1"))
    throw new java.lang.AssertionError("Incorrect name in spec file: rpm-ln-1 =! " + spec.name);

if (!spec.version.equals("1.0"))
    throw new java.lang.AssertionError("Incorrect version in spec file: 1.0 != " + spec.version);

if (!spec.release == 1)
    throw new java.lang.AssertionError("Incorrect release in spec file: 1 != " + spec.release);

List fileInfos = RpmUtil.queryPackageForFileInfo(rpm)

if (fileInfos.size() != 6)
    throw new java.lang.AssertionError("Incorrect file count: 6 != " + fileInfos.size());

nrLinks = 0
links = Arrays.asList("/usr/dir_link", "/usr/myusr/files/ccc.txt")

for (FileInfo fileInfo : fileInfos) {
    if (links.contains(fileInfo.path)) {
        if (!fileInfo.mode.startsWith("l"))
            throw new java.lang.AssertionError("${file.path} should be a link but file mode is: ${file.mode}")
        else
            nrLinks++
    }
}

if (nrLinks != links.size())
    throw new java.lang.AssertionError("Expected ${links.size()} links. Found ${nrLinks}")

return success
