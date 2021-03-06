/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.java;

import static com.facebook.buck.zip.ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

/**
 * Creates a JAR file from a collection of directories/ZIP/JAR files.
 */
public class JarDirectoryStep implements Step {

  /** Where to write the new JAR file. */
  private final String pathToOutputFile;

  /** A collection of directories/ZIP/JAR files to include in the generated JAR file. */
  private final ImmutableSet<String> entriesToJar;

  /** If specified, the Main-Class to list in the manifest of the generated JAR file. */
  @Nullable
  private final String mainClass;

  /** If specified, the Manifest file to use for the generated JAR file.  */
  @Nullable
  private final String manifestFile;

  /**
   * Creates a JAR from the specified entries (most often, classpath entries).
   * <p>
   * If an entry is a directory, then its files are traversed and added to the generated JAR.
   * <p>
   * If an entry is a file, then it is assumed to be a ZIP/JAR file, and its entries will be read
   * and copied to the generated JAR.
   * @param pathToOutputFile The directory that contains this path must exist before this command is
   *     executed.
   * @param entriesToJar Paths to directories/ZIP/JAR files.
   * @param mainClass If specified, the value for the Main-Class attribute in the manifest of the
   *     generated JAR.
   * @param manifestFile If specified, the path to the manifest file to use with this JAR.
   */
  public JarDirectoryStep(String pathToOutputFile,
                          Set<String> entriesToJar,
                          @Nullable String mainClass,
                          @Nullable String manifestFile) {
    this.pathToOutputFile = Preconditions.checkNotNull(pathToOutputFile);
    this.entriesToJar = ImmutableSet.copyOf(entriesToJar);
    this.mainClass = mainClass;
    this.manifestFile = manifestFile;
  }

  private String getJarArgs() {
    String result = "cf";
    if (manifestFile != null) {
      result += "m";
    }
    return result;
  }

  @Override
  public String getShortName() {
    return "jar";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("jar %s %s %s %s",
        getJarArgs(),
        pathToOutputFile,
        manifestFile != null ? manifestFile : "",
        Joiner.on(' ').join(entriesToJar));
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      createJarFile(context);
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
    return 0;
  }

  private void createJarFile(ExecutionContext context) throws IOException {
    ProjectFilesystem filesystem = context.getProjectFilesystem();

    // Write the manifest, as appropriate.
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

    try (CustomZipOutputStream outputFile = ZipOutputStreams.newOutputStream(
        filesystem.getFileForRelativePath(pathToOutputFile), APPEND_TO_ZIP)) {

      Set<String> alreadyAddedEntries = Sets.newHashSet();
      ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
      for (String entry : entriesToJar) {
        File file = projectFilesystem.getFileForRelativePath(entry);
        if (file.isFile()) {
          // Assume the file is a ZIP/JAR file.
          copyZipEntriesToJar(file,
              outputFile,
              manifest,
              alreadyAddedEntries,
              context.getBuckEventBus());
        } else if (file.isDirectory()) {
          addFilesInDirectoryToJar(file, outputFile, alreadyAddedEntries, context.getBuckEventBus());
        } else {
          throw new IllegalStateException("Must be a file or directory: " + file);
        }
      }

      // Read the user supplied manifest file, allowing it to overwrite existing entries in the
      // uber manifest we've built.
      if (manifestFile != null) {
        try (FileInputStream manifestStream = new FileInputStream(
            filesystem.getFileForRelativePath(manifestFile))) {
          Manifest userSupplied = new Manifest(manifestStream);
          merge(manifest, userSupplied);
        }
      }

      // The process of merging the manifests means that existing entries are
      // overwritten. To ensure that our main_class is set as expected, we
      // write it here.
      if (mainClass != null) {
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
      }

      JarEntry manifestEntry = new JarEntry(JarFile.MANIFEST_NAME);
      outputFile.putNextEntry(manifestEntry);
      manifest.write(outputFile);
    }
  }

  /**
   * @param file is assumed to be a zip file.
   * @param jar is the file being written.
   * @param manifest that should get a copy of (@code jar}'s manifest entries.
   * @param alreadyAddedEntries is used to avoid duplicate entries.
   */
  private void copyZipEntriesToJar(File file,
      final CustomZipOutputStream jar,
      Manifest manifest,
      Set<String> alreadyAddedEntries,
      BuckEventBus eventBus) throws IOException {
    try (ZipFile zip = new ZipFile(file)) {
      for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
        ZipEntry entry = entries.nextElement();
        String entryName = entry.getName();

        if (entryName.equals(JarFile.MANIFEST_NAME)) {
          Manifest readManifest = readManifest(zip, entry);
          merge(manifest, readManifest);
          continue;
        }

        // We're in the process of merging a bunch of different jar files. These typically contain
        // just ".class" files and the manifest, but they can also include things like license files
        // from third party libraries and config files. We should include those license files within
        // the jar we're creating. Extracting them is left as an exercise for the consumer of the jar.
        // Because we don't know which files are important, the only ones we skip are duplicate class
        // files.
        if (!isDuplicateAllowed(entryName) && !alreadyAddedEntries.add(entryName)) {
          // Duplicate entries. Skip.
          eventBus.post(LogEvent.create(
              determineSeverity(entry), "Duplicate found when adding file to jar: %s", entryName));
          continue;
        }

        // Reinitialize the compressed field to -1 as the ZipEntry(String) constructor would.
        // See https://github.com/spearce/buck/commit/8338c1c3d4a546f577eed0c9941d9f1c2ba0a1b7.
        ZipEntry newEntry = new ZipEntry(entry);
        newEntry.setCompressedSize(-1);

        jar.putNextEntry(newEntry);
        InputStream inputStream = zip.getInputStream(entry);
        ByteStreams.copy(inputStream, jar);
        jar.closeEntry();
      }
    }
  }

  private Level determineSeverity(ZipEntry entry) {
    return entry.isDirectory() ? Level.FINE : Level.INFO;
  }

  private Manifest readManifest(ZipFile zip, ZipEntry manifestMfEntry) throws IOException {
    try (
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) manifestMfEntry.getSize());
        InputStream stream = zip.getInputStream(manifestMfEntry);
    ) {
      ByteStreams.copy(stream, output);
      ByteArrayInputStream rawManifest = new ByteArrayInputStream(output.toByteArray());
      return new Manifest(rawManifest);
    }
  }

  /**
   * @param directory that must not contain symlinks with loops.
   * @param jar is the file being written.
   */
  private void addFilesInDirectoryToJar(File directory,
      final CustomZipOutputStream jar,
      final Set<String> alreadyAddedEntries,
      final BuckEventBus eventBus) throws IOException {
    new DirectoryTraversal(directory) {

      @Override
      public void visit(File file, String relativePath) {
        JarEntry entry = new JarEntry(relativePath);
        String entryName = entry.getName();
        entry.setTime(file.lastModified());
        try {
          // We expect there to be many duplicate entries for things like directories. Creating
          // those repeatedly would be lame, so don't do that.
          if (!isDuplicateAllowed(entryName) && !alreadyAddedEntries.add(entryName)) {
            if (!entryName.endsWith("/")) {
              eventBus.post(LogEvent.create(
                  determineSeverity(entry),
                  "Duplicate found when adding directory to jar: %s", relativePath));
            }
              return;
          }
          jar.putNextEntry(entry);
          Files.copy(file, jar);
          jar.closeEntry();
        } catch (IOException e) {
          Throwables.propagate(e);
        }
      }

    }.traverse();
  }

  /**
   * Merge entries from two Manifests together, with existing attributes being
   * overwritten.
   *
   * @param into The Manifest to modify.
   * @param from The Manifest to copy from.
   */
  private void merge(Manifest into, Manifest from) {
    Preconditions.checkNotNull(into);
    Preconditions.checkNotNull(from);

    Attributes attributes = from.getMainAttributes();
    if (attributes != null) {
      for (Map.Entry<Object, Object> attribute : attributes.entrySet()) {
        into.getMainAttributes().put(attribute.getKey(), attribute.getValue());
      }
    }

    Map<String, Attributes> entries = from.getEntries();
    if (entries != null) {
      for (Map.Entry<String, Attributes> entry : entries.entrySet()) {
        into.getEntries().put(entry.getKey(), entry.getValue());
      }
    }
  }

  private boolean isDuplicateAllowed(String name) {
    return !name.endsWith(".class") && !name.endsWith("/");
  }
}
