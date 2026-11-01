package app.openauthenticator.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UpdateCheckerTest {
    @Test public void comparesGitHubTagsToInstalledVersion() {
        assertTrue(UpdateChecker.compare("v1.0.9", "1.0.8") > 0);
        assertEquals(0, UpdateChecker.compare("v1.0.8", "1.0.8"));
        assertTrue(UpdateChecker.compare("v1.0.7", "1.0.8") < 0);
        assertTrue(UpdateChecker.compare("v1.1.0", "1.0.9") > 0);
    }
}
