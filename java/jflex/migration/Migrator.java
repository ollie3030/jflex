package jflex.migration;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.CaseFormat;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.google.common.flogger.LoggerConfig;
import com.google.common.io.CharSink;
import com.google.common.io.CharSource;
import com.google.common.io.Files;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.logging.Level;
import jflex.testing.testsuite.golden.GoldenInOutFilePair;
import jflex.util.javac.JavaPackageUtil;
import jflex.velocity.Velocity;
import org.apache.velocity.runtime.parser.ParseException;

/**
 * Tool to migrate a test case from {@code //testsuite/testcases/src/test/cases} (as executed by the
 * jflex-testsuite-amven-plugin) to {@code //ajvatests/jflex/testcase} (as executed by bazel).
 *
 * <p>See <a href="README.md">README</a> for usage.
 */
public class Migrator {

  private static final String PATH = JavaPackageUtil.getPathForClass(Migrator.class);
  private static final String TEST_CASE_TEMPLATE = PATH + "/TestCase.java.vm";
  private static final String BUILD_TEMPLATE = PATH + "/BUILD.vm";
  private static final String GOLDEN_INPUT_EXT = ".input";
  private static final String GOLDEN_OUTPUT_EXT = ".output";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static void main(String[] args) {
    LoggerConfig.of(logger).setLevel(Level.FINEST);
    checkArgument(args.length > 0, "Syntax error: migrator TESTCASE_DIRS_ABS_PATH");
    try {
      for (String testCaseDir : args) {
        migrateCase(testCaseDir);
      }
    } catch (MigrationException e) {
      logger.atSevere().withCause(e).log("Migration failed");
    }
  }

  /** Migrates one given test-case directory. */
  private static void migrateCase(String testCase) throws MigrationException {
    File dir = new File(testCase);
    if (!dir.exists()) {
      logger.atWarning().log("Directory doesn't exist: " + dir.getName());
      throw new MigrationException(
          "Could not migrate " + testCase, new FileNotFoundException(dir.getAbsolutePath()));
    }
    migrateCase(dir);
  }

  /** Migrates one given test-case directory. */
  private static void migrateCase(File testCaseDir) throws MigrationException {
    logger.atInfo().log("Migrating %s...", testCaseDir.getName());
    logger.atFine().log("location: %s", testCaseDir.getAbsolutePath());
    Iterable<File> directoryContent = Files.fileTraverser().breadthFirst(testCaseDir);
    ImmutableList<File> testSpecFiles =
        Streams.stream(directoryContent)
            .filter(f -> Files.getFileExtension(f.getName()).equals("test"))
            .collect(toImmutableList());
    for (File testSpec : testSpecFiles) {
      migrateTestCase(testCaseDir, testSpec);
    }
  }

  /**
   * Migrates one given test case (such as {@code test-0}) within the test case directory.
   *
   * <p>Scans the grammar specification and creates a {@link TestCase} model.
   */
  private static void migrateTestCase(File testCaseDir, File testSpecFile)
      throws MigrationException {
    try (BufferedReader reader = Files.newReader(testSpecFile, Charsets.UTF_8)) {
      TestSpecScanner scanner = new TestSpecScanner(reader);
      TestCase test = scanner.load();
      if (test.isExpectJavacFail() || test.isExpectJFlexFail()) {
        logger.atWarning().log("Test %s must be migrated with JflexTestRunner", test.getTestName());
      }
      migrateTestCase(testCaseDir, test);
    } catch (IOException e) {
      throw new MigrationException("Failed reading the test spec " + testSpecFile.getName(), e);
    }
  }

  /**
   * Migrates one given test case (such as {@code test-0}), represented by a {@link TestCase} data
   * model, within the test case directory.
   *
   * <p>Creates all velocity {@link MigrationTemplateVars} for this case.
   */
  private static void migrateTestCase(File testCaseDir, TestCase test) throws MigrationException {
    String lowerUnderscoreTestDir =
        CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_UNDERSCORE, testCaseDir.getName());
    File flexFile = findFlexFile(testCaseDir, test);
    ImmutableList<GoldenInOutFilePair> goldenFiles = findGoldenFiles(testCaseDir, test);
    MigrationTemplateVars templateVars =
        createTemplateVars(lowerUnderscoreTestDir, test, flexFile, goldenFiles);
    migrateTestCase(lowerUnderscoreTestDir, templateVars);
  }

  /**
   * Migrates one given test case.
   *
   * <ol>
   *   <li>Creates a target folder in {@code /tmp} based on the original directory name (replaces
   *       '-' by '_')
   *   <li>Generates a build file
   *   <li><Renders the java test class
   *   <li>Modifies and copies the flex grammar in the target folder. See {@link
   *       #copyGrammarFile(File, String, File)}
   *   <li>Copies the golden files
   * </ol>
   */
  private static void migrateTestCase(String targetTestDir, MigrationTemplateVars templateVars)
      throws MigrationException {
    File outputDir = new File(new File("/tmp"), targetTestDir);
    if (!outputDir.isDirectory()) {
      //noinspection ResultOfMethodCallIgnored
      outputDir.mkdirs();
    }
    logger.atInfo().log("Generating into %s", outputDir);
    renderBuildFile(templateVars, outputDir);
    renderTestCase(templateVars, outputDir);
    copyGrammarFile(templateVars.flexGrammar, templateVars.javaPackage, outputDir);
    copyGoldenFiles(templateVars.goldens, outputDir);
    logger.atInfo().log("Import the files in your workspace");
    logger.atInfo().log("   cp -r %s $(bazel info workspace)/javatests/jflex/testcase", outputDir);
  }

  /** Generates the BUILD file for this test case. */
  // FIXME This should be done once for the whole directory, but with many java_test()
  private static void renderBuildFile(MigrationTemplateVars templateVars, File outputDir)
      throws MigrationException {
    File outFile = new File(outputDir, "BUILD");
    try {
      // If there are multiple `.test` files in a directory, this is going to break.
      Preconditions.checkState(!outFile.exists(), "Attempting to override an existing BUILD file");
    } catch (IllegalStateException e) {
      // throw new MigrationException("Please output to a clean directory", e);
      logger.atWarning().log("Overriding %s", outFile);
    }
    try (OutputStream outputStream = new FileOutputStream(outFile)) {
      logger.atInfo().log("Generating %s", outFile);
      velocityRenderBuildFile(templateVars, outputStream);
    } catch (IOException e) {
      throw new MigrationException("Couldn't write BUILD file", e);
    }
  }

  /** Generates the Java test class. */
  private static void renderTestCase(MigrationTemplateVars templateVars, File outputDir)
      throws MigrationException {
    File outFile = new File(outputDir, templateVars.testClassName + ".java");
    try (OutputStream outputStream = new FileOutputStream(outFile)) {
      logger.atInfo().log("Generating %s", outFile);
      velocityRenderTestCase(templateVars, outputStream);
    } catch (IOException e) {
      throw new MigrationException("Couldn't write java test case", e);
    }
  }

  /**
   * Copy the grammar file.
   *
   * <p>The old grammars were defined in the default (empty) java package. The copied file be
   * prepended with a {@code package} declaration matches its directory location.
   */
  private static void copyGrammarFile(File flexFile, String javaPackage, File outputDir)
      throws MigrationException {
    try {
      logger.atInfo().log("Copy grammar %s", flexFile.getName());
      logger.atFine().log("location: %s", flexFile.getAbsolutePath());
      // The grammars are defined in the default package. This is so bad practice that I'm not
      // sure that bazel allows compilation. Don't simply copy the original:
      // copyFile(fixedFlexFile, outputDir);
      // But instead:
      File copiedWithPatch = new File(outputDir, flexFile.getName());
      CharSink out = Files.asCharSink(copiedWithPatch, Charsets.UTF_8);
      CharSource fixedContent =
          CharSource.concat(
              CharSource.wrap(String.format("package %s;\n", javaPackage)),
              Files.asCharSource(flexFile, Charsets.UTF_8));

      fixedContent.copyTo(out);
    } catch (IOException e) {
      throw new MigrationException("Could not copy .flex file", e);
    }
  }

  /** Copy the list of golden files. */
  private static void copyGoldenFiles(ImmutableList<GoldenInOutFilePair> goldens, File outputDir)
      throws MigrationException {
    logger.atInfo().log("Copy %d pairs of golden files", goldens.size());
    try {
      for (GoldenInOutFilePair golden : goldens) {
        copyFile(golden.inputFile, outputDir);
        copyFile(golden.outputFile, outputDir);
      }
    } catch (IOException e) {
      throw new MigrationException("Could not copy golden files", e);
    }
  }

  /** Creates the template variables for velocity. */
  private static MigrationTemplateVars createTemplateVars(
      String lowerUnderscoreTestDir,
      TestCase test,
      File flexGrammar,
      ImmutableList<GoldenInOutFilePair> goldenFiles) {
    MigrationTemplateVars vars = new MigrationTemplateVars();
    vars.flexGrammar = flexGrammar;
    vars.javaPackage = "jflex.testcase." + lowerUnderscoreTestDir;
    vars.javaPackageDir = "jflex/testcase/" + lowerUnderscoreTestDir;
    vars.testClassName =
        CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_CAMEL, test.getTestName()) + "GoldenTest";
    vars.testName = test.getTestName();
    vars.testDescription = test.getDescription().trim();
    vars.goldens = goldenFiles;
    // TODO(regisd). We should use the real JFLex generator to read the `%class` value from the
    // grammar. For now, we rely on the convention that the name of the scanner is the name of
    // the test...
    vars.scannerClassName = CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_CAMEL, test.getTestName());
    return vars;
  }

  /** Lists all golden files for a given test. */
  private static ImmutableList<GoldenInOutFilePair> findGoldenFiles(
      File testCaseDir, TestCase test) {
    Iterable<File> dirContent = Files.fileTraverser().breadthFirst(testCaseDir);
    return Streams.stream(dirContent)
        .filter(f -> isGoldenInputFile(test, f))
        .map(f -> new GoldenInOutFilePair(f, getGoldenOutputFile(f)))
        .filter(g -> g.outputFile.isFile())
        .collect(toImmutableList());
  }

  /** Finds the grammar file for a test. */
  private static File findFlexFile(File testCaseDir, TestCase test) {
    return new File(testCaseDir, test.getTestName() + ".flex");
  }

  private static boolean isGoldenInputFile(TestCase test, File f) {
    return f.getName().startsWith(test.getTestName() + "-") && f.getName().endsWith(".input");
  }

  /** Returns the output file for the given input file. */
  private static File getGoldenOutputFile(File goldenInputFIle) {
    checkArgument(goldenInputFIle.getName().endsWith(GOLDEN_INPUT_EXT));
    return new File(
        goldenInputFIle.getParentFile(),
        goldenInputFIle
                .getName()
                .substring(0, goldenInputFIle.getName().length() - ".input".length())
            + GOLDEN_OUTPUT_EXT);
  }

  /** Invokes velocity to generate the BUILD file. */
  private static void velocityRenderBuildFile(
      MigrationTemplateVars templateVars, OutputStream output)
      throws IOException, MigrationException {
    try (Writer writer = new BufferedWriter(new OutputStreamWriter(output))) {
      Velocity.render(readResource(BUILD_TEMPLATE), "BuildBazel", templateVars, writer);
    } catch (ParseException e) {
      throw new MigrationException("Failed to parse Velocity template " + BUILD_TEMPLATE, e);
    }
  }

  /** Invokes velocity to generate the Test file. */
  private static void velocityRenderTestCase(
      MigrationTemplateVars templateVars, OutputStream output)
      throws IOException, MigrationException {
    try (Writer writer = new BufferedWriter(new OutputStreamWriter(output))) {
      Velocity.render(readResource(TEST_CASE_TEMPLATE), "TestCase", templateVars, writer);
    } catch (ParseException e) {
      throw new MigrationException("Failed to parse Velocity template " + TEST_CASE_TEMPLATE, e);
    }
  }

  /** Copies file to the target directory. */
  private static void copyFile(File file, File targetDir) throws IOException {
    checkArgument(file.isFile(), "Input %s should be a file: %s", file, file.getAbsoluteFile());
    checkArgument(
        targetDir.isDirectory(),
        "Target %s should be a directory: %s",
        targetDir,
        targetDir.getAbsoluteFile());
    logger.atFine().log("Copying %s...", file.getName());
    File copiedFile = new File(targetDir, file.getName());
    Files.copy(file, copiedFile);
  }

  private static InputStreamReader readResource(String resourceName) {
    InputStream resourceAsStream =
        checkNotNull(
            ClassLoader.getSystemClassLoader().getResourceAsStream(resourceName),
            "Null resource content for " + resourceName);
    return new InputStreamReader(resourceAsStream);
  }

  private Migrator() {}
}