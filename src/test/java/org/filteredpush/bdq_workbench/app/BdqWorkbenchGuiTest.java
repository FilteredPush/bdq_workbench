package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class BdqWorkbenchGuiTest {

    @Test
    void cacheNameIncludesSourceFileName() throws Exception {
        Method method = BdqWorkbenchGui.class.getDeclaredMethod("cacheNameFor", String.class);
        method.setAccessible(true);

        String name = (String) method.invoke(null, "https://bdq.tdwg.org/draft/dist/bdquc.xml");

        assertThat(name).startsWith("bdquc-cached-").endsWith(".xml");
    }
}
