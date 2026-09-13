package eu.wohlben.qits.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * This repository declares its own configuration in {@code .config/qits/configuration.yml}.
 *
 * <p>This test is NOT a parser. qits-configuration's {@code DeclarationParser} owns the grammar. It
 * holds only what a hand edit can break without anyone noticing: the file is where the deployer
 * fetches it, the top level is {@code keys:} alone, every key name has the {@code env.<VAR>} shape,
 * no key is declared twice, and no key has a default. A default would be layered under the stored
 * entries and never seen, so the file must stay inert.
 */
class OwnDeclarationTest {

  private static final String DECLARATION_PATH = ".config/qits/configuration.yml";

  private static final Pattern ENV_KEY = Pattern.compile("^env\\.[A-Za-z_][A-Za-z0-9_]*$");

  private static final Set<String> TYPES = Set.of("string", "boolean", "number");

  /** Surefire starts in the module directory, so walk up to the repository root. */
  private static List<String> lines() throws IOException {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      Path candidate = at.resolve(DECLARATION_PATH);
      if (Files.isRegularFile(candidate)) {
        return Files.readAllLines(candidate);
      }
    }
    throw new AssertionError("no " + DECLARATION_PATH + " above " + Path.of("").toAbsolutePath());
  }

  private static boolean content(String line) {
    return !line.isBlank() && !line.strip().startsWith("#");
  }

  @Test
  void theTopLevelIsKeysAlone() throws IOException {
    List<String> topLevel = new ArrayList<>();
    for (String line : lines()) {
      if (content(line) && !line.startsWith(" ")) {
        topLevel.add(line);
      }
    }

    assertEquals(List.of("keys:"), topLevel);
  }

  @Test
  void everyKeyIsAnEnvKeyDeclaredOnce() throws IOException {
    Set<String> seen = new LinkedHashSet<>();
    for (String line : lines()) {
      if (content(line) && line.startsWith("  ") && !line.startsWith("   ")) {
        String name = line.strip();
        assertTrue(name.endsWith(":"), "not a key line: " + line);
        name = name.substring(0, name.length() - 1);
        assertTrue(ENV_KEY.matcher(name).matches(), "not an env.<VAR> key: " + name);
        assertTrue(seen.add(name), "declared twice: " + name);
      }
    }

    assertFalse(seen.isEmpty(), "the document declares no keys");
  }

  @Test
  void everyKeyHasAPlainTypeAndNoDefault() throws IOException {
    for (String line : lines()) {
      if (!content(line) || !line.startsWith("    ")) {
        continue;
      }
      String attribute = line.strip();
      assertFalse(attribute.startsWith("default:"), "a default makes the file non-inert: " + line);
      if (attribute.startsWith("type:")) {
        String type = attribute.substring("type:".length()).strip();
        assertTrue(TYPES.contains(type), "only string, boolean or number here: " + line);
      } else {
        assertTrue(attribute.startsWith("description:"), "unexpected attribute: " + line);
      }
    }
  }
}
