package dev.sentinel.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Changes a single value in the TOML text while keeping the rest of the file, comments included.
 * Only used for Discord IDs, so the value never needs escaping.
 */
final class ConfigEditor {
	private ConfigEditor() {
	}

	static String set(String toml, String section, String key, String value) {
		if (!value.matches("\\d*")) throw new IllegalArgumentException("Not a Discord ID: " + value);
		String newline = toml.contains("\r\n") ? "\r\n" : "\n";
		List<String> lines = new ArrayList<>(Arrays.asList(toml.split("\r?\n", -1)));
		String entry = key + " = \"" + value + "\"";
		Pattern keyLine = Pattern.compile("\\s*" + Pattern.quote(key) + "\\s*=.*");
		Pattern header = Pattern.compile("\\s*\\[\\s*" + Pattern.quote(section) + "\\s*]\\s*(#.*)?");

		int sectionStart = -1;
		String openQuote = null;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (openQuote != null) {
				// Inside a multi-line string such as log_message.
				if (count(line, openQuote) % 2 == 1) openQuote = null;
				continue;
			}
			String trimmed = line.strip();
			if (trimmed.startsWith("[")) {
				if (sectionStart >= 0) {
					// Key missing from its section: add it at the end of the section.
					int at = i;
					while (at > sectionStart + 1 && lines.get(at - 1).isBlank()) at--;
					lines.add(at, entry);
					return String.join(newline, lines);
				}
				if (header.matcher(line).matches()) sectionStart = i;
				continue;
			}
			if (trimmed.startsWith("#")) continue;
			if (sectionStart >= 0 && keyLine.matcher(line).matches()) {
				lines.set(i, entry);
				return String.join(newline, lines);
			}
			for (String quote : new String[] {"\"\"\"", "'''"}) {
				if (count(line, quote) % 2 == 1) {
					openQuote = quote;
					break;
				}
			}
		}

		if (sectionStart >= 0) {
			int at = lines.size();
			while (at > sectionStart + 1 && lines.get(at - 1).isBlank()) at--;
			lines.add(at, entry);
		} else {
			if (!lines.getLast().isBlank()) lines.add("");
			lines.add("[" + section + "]");
			lines.add(entry);
			lines.add("");
		}
		return String.join(newline, lines);
	}

	private static int count(String line, String quote) {
		int n = 0;
		for (int i = line.indexOf(quote); i >= 0; i = line.indexOf(quote, i + quote.length())) n++;
		return n;
	}
}
