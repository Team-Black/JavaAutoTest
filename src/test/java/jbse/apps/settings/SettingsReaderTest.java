package jbse.apps.settings;

//import static org.junit.Assert.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import jbse.jvm.EngineParameters;

import org.junit.Test;

public class SettingsReaderTest {
	@Test
	public void testSimple() throws FileNotFoundException, ParseException, IOException {
		final Path javaHome = Paths.get("C:\\Program Files\\AdoptOpenJDK\\jdk-8.0.242.08-hotspot\\jre");
		final SettingsReader r = new SettingsReader(Paths.get("src", "test", "resources", "jbse", "apps", "settings", "testdata", "foo.jbse"));
		final EngineParameters p = new EngineParameters(javaHome);
		r.fillEngineParameters(p);
		
		//TODO assertEquals...
	}
}
