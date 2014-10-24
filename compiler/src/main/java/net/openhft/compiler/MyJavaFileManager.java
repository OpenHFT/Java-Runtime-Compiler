/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.compiler;

import org.jetbrains.annotations.NotNull;

import javax.tools.*;
import javax.tools.JavaFileObject.Kind;
import java.io.*;
import java.net.URI;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("RefusedBequest")
class MyJavaFileManager implements JavaFileManager {
    private final StandardJavaFileManager fileManager;
    private final Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<String, ByteArrayOutputStream>();

    MyJavaFileManager(StandardJavaFileManager fileManager) {
        this.fileManager = fileManager;
    }

    public ClassLoader getClassLoader(Location location) {
        // System.out.println("getClassLoader(" + location + ')');
        return fileManager.getClassLoader(location);
    }

    public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
        // System.out.println("list(" + location + ',' + packageName + ',' + kinds + ',' + recurse + ')');
        return fileManager.list(location, packageName, kinds, recurse);
    }

    public String inferBinaryName(Location location, JavaFileObject file) {
//        System.out.println("inferBinaryName(location=" + location + ", file=" + file + ')');
        return fileManager.inferBinaryName(location, file);
    }

    public boolean isSameFile(FileObject a, FileObject b) {
        return fileManager.isSameFile(a, b);
    }

    public boolean handleOption(String current, Iterator<String> remaining) {
        return fileManager.handleOption(current, remaining);
    }

    public boolean hasLocation(Location location) {
        // System.out.println("hasLocation(" + location + ')');
        return fileManager.hasLocation(location);
    }

    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
        // System.out.println("getJavaFileForInput(location=" + location + ", className=" + className + ", kind=" + kind + ')');
        if (location == StandardLocation.CLASS_OUTPUT && buffers.containsKey(className) && kind == Kind.CLASS) {
            final byte[] bytes = buffers.get(className).toByteArray();
            return new SimpleJavaFileObject(URI.create(className), kind) {
                @NotNull
                public InputStream openInputStream() {
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
        return fileManager.getJavaFileForInput(location, className, kind);
    }

    @NotNull
    public JavaFileObject getJavaFileForOutput(Location location, final String className, Kind kind, FileObject sibling) throws IOException {
        return new SimpleJavaFileObject(URI.create(className), kind) {
            @NotNull
            public OutputStream openOutputStream() {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                buffers.put(className, baos);
                return baos;
            }
        };
    }

    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        return fileManager.getFileForInput(location, packageName, relativeName);
    }

    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling) throws IOException {
        return fileManager.getFileForOutput(location, packageName, relativeName, sibling);
    }

    public void flush() throws IOException {
//            for (Map.Entry<String, ByteArrayOutputStream> entry : buffers.entrySet())
//                System.out.println("Compiled " + entry.getKey() + ", size=" + entry.getValue().toByteArray().length);
    }

    public void close() throws IOException {
        // System.out.println("close()");
        fileManager.close();
    }

    public int isSupportedOption(String option) {
        return fileManager.isSupportedOption(option);
    }

    public void clearBuffers() {
        buffers.clear();
    }

    @NotNull
    public Map<String, byte[]> getAllBuffers() {
        Map<String, byte[]> ret = new LinkedHashMap<String, byte[]>(buffers.size() * 2);
        for (Map.Entry<String, ByteArrayOutputStream> entry : buffers.entrySet()) {
            ret.put(entry.getKey(), entry.getValue().toByteArray());
        }
        return ret;
    }
}
