/*
 * Copyright 2025 chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package external;

import net.openhft.compiler.CachedCompiler;
import net.openhft.compiler.MyJavaFileManager;
import org.junit.Test;

import javax.tools.ToolProvider;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CachedCompilerBinaryCompatibilityTest {

    @Test
    public void publicFieldAccessibleAcrossPackages() throws Exception {
        assertNotNull("System compiler required", ToolProvider.getSystemJavaCompiler());

        CachedCompiler cachedCompiler = new CachedCompiler(null, null);
        AtomicBoolean invoked = new AtomicBoolean(false);

        cachedCompiler.fileManagerOverride = fm -> {
            invoked.set(true);
            return new MyJavaFileManager(fm);
        };

        cachedCompiler.loadFromJava("bincompat.Sample",
                "package bincompat; public class Sample { }");

        assertTrue("Public field assignment should be respected", invoked.get());
    }
}
