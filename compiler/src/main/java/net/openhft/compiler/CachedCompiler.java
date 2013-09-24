/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.compiler;

import org.jetbrains.annotations.NotNull;

import javax.tools.JavaFileObject;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;

import static net.openhft.compiler.CompilerUtils.writeBytes;
import static net.openhft.compiler.CompilerUtils.writeText;

@SuppressWarnings("StaticNonFinalField")
public class CachedCompiler {
    private final File sourceDir;
    private final File classDir;

    public CachedCompiler(@NotNull File sourceDir, @NotNull File classDir) {
        this.sourceDir = sourceDir;
        this.classDir = classDir;
    }

    public static void close() {
        try {
            CompilerUtils.s_fileManager.close();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    public Class loadFromJava(@NotNull String className, @NotNull String javaCode) throws ClassNotFoundException {
        return loadFromJava(getClass().getClassLoader(), className, javaCode);
    }

    @NotNull
    Map<String, byte[]> compileFromJava(@NotNull String className, @NotNull String javaCode) {
        Iterable<? extends JavaFileObject> compilationUnits;
        if (sourceDir != null) {
            String filename = className.replaceAll("\\.", '\\' + File.separator) + ".java";
            File file = new File(sourceDir, filename);
            writeText(file, javaCode);
            compilationUnits = CompilerUtils.s_standardJavaFileManager.getJavaFileObjects(file);
        } else {
            compilationUnits = Collections.singletonList(new JavaSourceFromString(className, javaCode));
        }
        // reuse the same file manager to allow caching of jar files
        CompilerUtils.s_compiler.getTask(null, CompilerUtils.s_fileManager, null, null, null, compilationUnits).call();
        return CompilerUtils.s_fileManager.getAllBuffers();
    }

    public Class loadFromJava(@NotNull ClassLoader classLoader, @NotNull String className, @NotNull String javaCode) throws ClassNotFoundException {
        for (Map.Entry<String, byte[]> entry : compileFromJava(className, javaCode).entrySet()) {
            String className2 = entry.getKey();
            byte[] bytes = entry.getValue();
            if (classDir != null) {
                String filename = className2.replaceAll("\\.", '\\' + File.separator) + ".class";
                boolean changed = writeBytes(new File(classDir, filename), bytes);
                if (changed)
                    Logger.getLogger(CachedCompiler.class.getName()).info("Updated " + className2 + " in " + classDir);
            }
            CompilerUtils.defineClass(classLoader, className2, bytes);
        }
        CompilerUtils.s_fileManager.clearBuffers();
        return classLoader.loadClass(className);
    }
}
