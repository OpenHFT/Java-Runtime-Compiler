package net.openhft.compiler;

import junit.framework.TestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class CachedCompilerTest extends TestCase {
    private CachedCompiler cachedCompiler;
    private StandardJavaFileManager standardJavaFileManager;

    @BeforeEach
    public void setUp() {
        cachedCompiler = new CachedCompiler(null, null);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        standardJavaFileManager = compiler.getStandardFileManager(null, null, null);
    }

    @AfterEach
    public void tearDown() throws IOException {
        cachedCompiler.close();
    }

    @Test
    public void testLoadFromJavaWithClassLoaderClassNameAndCode() {
        String className = "net.openhft.compiler.TestClassB";
        String javaCode = "package net.openhft.compiler; public class TestClassB {}";
        ClassLoader customClassLoader = new ClassLoader(getClass().getClassLoader()) {
        };
        assertDoesNotThrow(() -> cachedCompiler.loadFromJava(customClassLoader, className, javaCode));
    }

    @Test
    public void testCompileFromJava() {
        String className = "net.openhft.compiler.TestClass3";
        String javaCode = "package net.openhft.compiler; public class TestClass3 {}";
        MyJavaFileManager fileManager = new MyJavaFileManager(standardJavaFileManager);
        Map<String, byte[]> result = cachedCompiler.compileFromJava(className, javaCode, fileManager);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.containsKey(className));
    }

    @Test
    public void testCompileFromJavaWithWriter() {
        String className = "net.openhft.compiler.TestClass4";
        String javaCode = "package net.openhft.compiler; public class TestClass4 {}";
        PrintWriter writer = new PrintWriter(System.err);
        MyJavaFileManager fileManager = new MyJavaFileManager(standardJavaFileManager);
        Map<String, byte[]> result = cachedCompiler.compileFromJava(className, javaCode, writer, fileManager);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.containsKey(className));
    }

    @Test
    public void testLoadFromJavaWithWriter() {
        String className = "net.openhft.compiler.TestClass5";
        String javaCode = "package net.openhft.compiler; public class TestClass5 {}";
        PrintWriter writer = new PrintWriter(System.err);
        ClassLoader customClassLoader = new ClassLoader(getClass().getClassLoader()) {
        };
        assertDoesNotThrow(() -> cachedCompiler.loadFromJava(customClassLoader, className, javaCode, writer));
    }

    @Test
    public void testGetFileManager() {
        MyJavaFileManager fileManager = cachedCompiler.getFileManager(standardJavaFileManager);
        assertNotNull(fileManager);
    }

    @Test
    public void testClose() {
        assertDoesNotThrow(() -> cachedCompiler.close());
    }
}