package org.javacs;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import javax.tools.*;

public class Docs {

    /** File manager with source-path + platform sources, which we will use to look up individual source files */
    private final SourceFileManager fileManager = new SourceFileManager();

    Docs(Set<Path> docPath) {
        var srcZipPath = srcZip();
        // Path to source .jars + src.zip
        var sourcePath = new ArrayList<Path>(docPath);
        if (srcZipPath != NOT_FOUND) {
            sourcePath.add(srcZipPath);
        }
        try {
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, sourcePath);
            if (srcZipPath != NOT_FOUND) {
                fileManager.setLocationFromPaths(StandardLocation.MODULE_SOURCE_PATH, Set.of(srcZipPath));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<JavaFileObject> find(Ptr ptr) {
        LOG.info(String.format("...looking for file for `%s`...", ptr));

        // Find the file el was declared in
        var className = ptr.qualifiedClassName();
        try {
            var fromSourcePath =
                    fileManager.getJavaFileForInput(
                            StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);
            if (fromSourcePath != null) {
                LOG.info(String.format("...found %s on source path", fromSourcePath.toUri().getPath()));
                return Optional.of(fromSourcePath);
            }
            for (var module : Classes.JDK_MODULES) {
                var moduleLocation = fileManager.getLocationForModule(StandardLocation.MODULE_SOURCE_PATH, module);
                if (moduleLocation == null) continue;
                var fromModuleSourcePath =
                        fileManager.getJavaFileForInput(moduleLocation, className, JavaFileObject.Kind.SOURCE);
                if (fromModuleSourcePath != null) {
                    LOG.info(String.format("...found %s in module %s of jdk", fromModuleSourcePath.toUri(), module));
                    return Optional.of(fromModuleSourcePath);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        LOG.info(String.format("...couldn't find file for top-level class `%s`", className));
        return Optional.empty();
    }

    private static final Path NOT_FOUND = Paths.get("");
    private static Path cacheSrcZip;

    private static Path srcZip() {
        if (cacheSrcZip == null) {
            cacheSrcZip = findSrcZip();
        }
        if (cacheSrcZip == NOT_FOUND) {
            return NOT_FOUND;
        }
        try {
            var fs = FileSystems.newFileSystem(cacheSrcZip, Docs.class.getClassLoader());
            return fs.getPath("/");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path findSrcZip() {
        // TODO try something else when JAVA_HOME isn't defined
        var javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null) {
            LOG.warning("Couldn't find src.zip because JAVA_HOME is not defined");
            return NOT_FOUND;
        }
        String[] locations = {
            "lib/src.zip", "src.zip",
        };
        for (var rel : locations) {
            var abs = Paths.get(javaHome).resolve(rel);
            if (Files.exists(abs)) {
                LOG.info("Found " + abs);
                return abs;
            }
        }
        LOG.warning("Couldn't find src.zip in " + javaHome);
        return NOT_FOUND;
    }

    private static final Logger LOG = Logger.getLogger("org.javacs.main");
}
