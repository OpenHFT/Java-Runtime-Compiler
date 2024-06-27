package net.openhft.compiler;

import junit.framework.TestCase;
import javax.tools.JavaFileObject;

public class JavaSourceFromStringTest extends TestCase {

    private JavaSourceFromString javaSourceFromString;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        String name = "net.openhft.compiler.TestSource";
        String code = "package net.openhft.compiler; public class TestSource {}";
        javaSourceFromString = new JavaSourceFromString(name, code);
    }

    public void testGetCharContent() {
        boolean ignoreEncodingErrors = true;
        CharSequence content = javaSourceFromString.getCharContent(ignoreEncodingErrors);
        assertNotNull(content);
        assertEquals("package net.openhft.compiler; public class TestSource {}", content.toString());
    }

    public void testGetKind() {
        assertEquals(JavaFileObject.Kind.SOURCE, javaSourceFromString.getKind());
    }

    public void testToUri() {
        assertEquals("string:///net/openhft/compiler/TestSource.java", javaSourceFromString.toUri().toString());
    }
}
