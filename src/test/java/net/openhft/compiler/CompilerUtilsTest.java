package net.openhft.compiler;

import junit.framework.TestCase;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class CompilerUtilsTest extends TestCase {

    private File testFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        testFile = new File("testFile.txt");
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (testFile.exists()) {
            assertTrue(testFile.delete());
        }
    }

    @Test
    public void testDecodeUTF8() {
        byte[] bytes = "Hello, world!".getBytes(StandardCharsets.UTF_8);
        String result = CompilerUtils.decodeUTF8(bytes);
        assertEquals("Hello, world!", result);
    }

    @Test
    public void testClose() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("test".getBytes(StandardCharsets.UTF_8));
        CompilerUtils.close(baos);
        assertEquals("test", baos.toString(StandardCharsets.UTF_8.name()));
    }

    @Test
    public void testEncodeUTF8() {
        String text = "Hello, world!";
        byte[] bytes = CompilerUtils.encodeUTF8(text);
        assertNotNull(bytes);
        assertEquals(text, new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void testDefineClassWithClassNameAndBytes() {
        String className = "net.openhft.compiler.TestDefineClassA";
        String javaCode = "package net.openhft.compiler; public class TestDefineClassA {}";
        byte[] byteCode = compileClass(className, javaCode);
        assertNotNull(byteCode);
        ClassLoader customClassLoader = new ClassLoader(getClass().getClassLoader()) {
        };
        assertDoesNotThrow(() -> CompilerUtils.defineClass(customClassLoader, className, byteCode));
    }

    @Test
    public void testDefineClassWithClassLoaderClassNameAndBytes() {
        String className = "net.openhft.compiler.TestDefineClassB";
        String javaCode = "package net.openhft.compiler; public class TestDefineClassB {}";
        byte[] byteCode = compileClass(className, javaCode);
        assertNotNull(byteCode);
        ClassLoader customClassLoader = new ClassLoader(getClass().getClassLoader()) {
        };
        assertDoesNotThrow(() -> CompilerUtils.defineClass(customClassLoader, className, byteCode));
    }

    private byte[] compileClass(String className, String javaCode) {
        CachedCompiler cachedCompiler = new CachedCompiler(null, null);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager standardJavaFileManager = compiler.getStandardFileManager(null, null, null);
        MyJavaFileManager fileManager = new MyJavaFileManager(standardJavaFileManager);
        Map<String, byte[]> result = cachedCompiler.compileFromJava(className, javaCode, fileManager);
        assertNotNull(result);
        return result.get(className);
    }
}
