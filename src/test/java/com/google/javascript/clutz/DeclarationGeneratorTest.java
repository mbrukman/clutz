package com.google.javascript.clutz;

import static com.google.javascript.clutz.ProgramSubject.assertThatProgram;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class DeclarationGeneratorTest {
  /** Comments in .d.ts and .js golden files starting with '//!!' are stripped. */
  public static final Pattern GOLDEN_FILE_COMMENTS_REGEXP = Pattern.compile("(?m)^\\s*//!!.*\\n");

  public static final FilenameFilter JS = (dir, name) -> name.endsWith(".js");
  public static final FilenameFilter ZIP = (dir, name) -> name.endsWith(".zip");
  public static final FilenameFilter TS_SOURCES = (dir, name) -> name.endsWith(".ts");
  public static final FilenameFilter D_TS = (dir, name) -> name.endsWith(".d.ts");
  public static final FilenameFilter NO_EXTERNS = (dir, name) -> !name.endsWith(".externs.js");
  public static final FilenameFilter JS_NO_EXTERNS = matchAllFilters(JS, NO_EXTERNS);
  public static final FilenameFilter JS_NO_EXTERNS_OR_ZIP = matchAnyFilter(JS_NO_EXTERNS, ZIP);

  private static FilenameFilter matchAllFilters(
      FilenameFilter filter, FilenameFilter... moreFilters) {
    return (dir, name) ->
        filter.accept(dir, name) && Arrays.stream(moreFilters).allMatch(f -> f.accept(dir, name));
  }

  private static FilenameFilter matchAnyFilter(
      FilenameFilter filter, FilenameFilter... moreFilters) {
    return (dir, name) ->
        filter.accept(dir, name) || Arrays.stream(moreFilters).anyMatch(f -> f.accept(dir, name));
  }

  public static final String PLATFORM_MARKER = "/** Insert general_with_platform.d.ts here */\n";

  @Parameters(name = "{index}: {0}")
  public static Iterable<File> testCases() {
    return getTestInputFiles(JS_NO_EXTERNS_OR_ZIP);
  }

  private static ProgramSubject createProgramSubject(File input) {
    ProgramSubject subject = assertThatProgram(input);
    if (input.getName().contains("_with_platform")) {
      subject.withPlatform = true;
    }
    if (input.getName().contains("_output_base")) {
      subject.emitBase = true;
    }
    if (Arrays.asList("partial", "multifilePartial", "partialCrossModuleTypeImports")
        .contains(input.getParentFile().getName())) {
      subject.partialInput = true;
      subject.debug = false;
    }
    if (input.getParentFile().getName().equals("partialCrossModuleTypeImports")) {
      subject.depgraph = "partialCrossModuleTypeImports/cross_module_type.depgraph";
    }
    // using async/await causes warnings inside closure's standard library, so ignore them for our
    // tests
    if (input.getName().contains("async")) {
      subject.debug = false;
    }

    subject.extraExternFile = getExternFileNameOrNull(input.getName());
    return subject;
  }

  public static File normalizeAndReplaceExt(final File input, String ext) {
    // Declarations can also be generated by ZIP files that contain .JS files beause closure
    // supports path/to/foo.zip!/ipath/in/zip.js, for testing purposes, we expect the golden files
    // to be generated on path/to/ipath/in/zip.d.ts
    String pathToGolden = input.getPath().replaceAll("\\w+.zip!.*/", "");

    return new File(pathToGolden.replaceAll("\\.js$", ext));
  }

  static String getExternFileNameOrNull(String testFileName) {
    String possibleFileName = testFileName.replace(".js", ".externs.js");
    Path externFile = getTestDataFolderPath().resolve(possibleFileName);
    return externFile.toFile().exists() ? externFile.toString() : null;
  }

  public static List<File> expandZipTestInputFiles(List<File> filesList) {
    Stream<File> jsFiles = filesList.stream().filter(f -> !f.getName().endsWith(".zip"));
    Stream<File> zipFiles =
        filesList.stream()
            .filter(f -> f.getName().endsWith(".zip"))
            .map(File::getPath)
            .map(DeclarationGenerator::getJsEntryPathsFromZip)
            .flatMap(List::stream)
            .map(Path::toFile);

    List<File> fileList = Stream.concat(jsFiles, zipFiles).collect(Collectors.toList());
    Collections.sort(fileList);
    return fileList;
  }

  public static List<File> getTestInputFiles(FilenameFilter filter) {
    File[] testFiles = getTestDataFolderPath().toFile().listFiles(filter);
    // Partial files live in 'partial' dir and run implicitly with the --partialInput option on.
    File[] testPartialFiles = getTestDataFolderPath().resolve("partial").toFile().listFiles(filter);
    // Test files that live in the 'multifilePartial' dir, and run with the --partialInput option
    // The resulting .d.ts files are checked with a DeclarationSyntaxTest, and they're also
    // compiled in a single run in MultiFileTest
    File[] testMultifilePartialFiles =
        getTestDataFolderPath().resolve("multifilePartial").toFile().listFiles(filter);
    // Test files that live in the 'testPartialCrossModuleTypeImportsFiles' dir, and run with the
    // --partialInput and --googProvides options.  The resulting .d.ts files are checked with a
    // DeclarationSyntaxTest, and they're also compiled in a single run in MultiFileTest
    File[] testPartialCrossModuleTypeImportsFiles =
        getTestDataFolderPath().resolve("partialCrossModuleTypeImports").toFile().listFiles(filter);
    // Output base files live in the 'outputBase' dir and impilicitly have base.js in their roots
    File[] testOutputBaseFiles =
        getTestDataFolderPath().resolve("outputBase").toFile().listFiles(filter);
    List<File> filesList = Lists.newArrayList(testFiles);
    filesList.addAll(Arrays.asList(testPartialFiles));
    filesList.addAll(Arrays.asList(testMultifilePartialFiles));
    filesList.addAll(Arrays.asList(testPartialCrossModuleTypeImportsFiles));
    filesList.addAll(Arrays.asList(testOutputBaseFiles));

    return expandZipTestInputFiles(filesList);
  }

  public static List<File> getTestInputFilesNoPartial(FilenameFilter filter) {
    File[] testFiles = getTestDataFolderPath().toFile().listFiles(filter);
    return Arrays.asList(testFiles);
  }

  static Path getTestInputFile(String fileName) {
    return getTestDataFolderPath().resolve(fileName);
  }

  private static Path getTestDataFolderPath() {
    Path root = FileSystems.getDefault().getPath(ProgramSubject.SOURCE_ROOT);
    Path testDir = root.resolve("src").resolve("test").resolve("java");
    String packageName = DeclarationGeneratorTest.class.getPackage().getName();
    return testDir.resolve(packageName.replace('.', File.separatorChar)).resolve("testdata");
  }

  static String getTestFileText(final File input) throws IOException {
    String text = Files.asCharSource(input, StandardCharsets.UTF_8).read();
    if (input.getName().endsWith("_with_platform.d.ts")) {
      File platformGolden = getTestDataFolderPath().resolve("general_with_platform.d.ts").toFile();
      String platformGoldenText = Files.asCharSource(platformGolden, StandardCharsets.UTF_8).read();
      if (text.contains(PLATFORM_MARKER)) {
        text = text.replace(PLATFORM_MARKER, platformGoldenText);
      } else {
        text += platformGoldenText;
      }
    }
    return text;
  }

  private final File input;

  public DeclarationGeneratorTest(File input) {
    this.input = input;
  }

  @Test
  public void runTest() throws Exception {
    File golden = normalizeAndReplaceExt(input, ".d.ts");
    createProgramSubject(input).generatesDeclarations(golden);
  }
}
