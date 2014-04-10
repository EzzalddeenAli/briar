package org.briarproject;

import java.io.File;
import java.io.IOException;

import org.briarproject.api.system.FileUtils;

public class TestFileUtils implements FileUtils {

	public long getTotalSpace(File f) throws IOException {
		return f.getTotalSpace(); // Requires Java 1.6
	}

	public long getFreeSpace(File f) throws IOException {
		return f.getUsableSpace(); // Requires Java 1.6
	}
}
