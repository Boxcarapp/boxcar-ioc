package com.boxcar.ioc.processor;

/*-
 * #%L
 * Boxcar IoC :: Processor
 * %%
 * Copyright (C) 2026 Boxcar
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the binary names of the classes below a set of package prefixes among compiled class files.
 *
 * <p>Annotation processors have no supported way of enumerating the compilation's class path, so this
 * class looks in two places: the directories and jars of the given {@code roots} (the value of the
 * {@code boxcar.classpath} option) and those of a class loader, which is the processor's own loader
 * and covers the whole compile class path whenever the processor is on it rather than on a separate
 * processor path. The class files themselves are never loaded; only their names are collected, and
 * the processor resolves them through the compiler's {@code Elements} API.
 */
final class ClassPathScanner {

    private final List<Path> roots = new ArrayList<>();

    ClassPathScanner(ClassLoader loader, Collection<Path> explicitRoots, Consumer<String> warnings) {
        for (Path root : explicitRoots) {
            if (Files.exists(root)) {
                roots.add(root);
            } else {
                warnings.accept("Class path entry " + root + " does not exist");
            }
        }
        roots.addAll(rootsOf(loader));
    }

    /** The roots being scanned, for diagnostics. */
    List<Path> roots() {
        return List.copyOf(roots);
    }

    /**
     * The binary names (dots for packages, {@code $} for nested classes) of all classes whose package is
     * {@code prefix} or a subpackage of it, sorted. Module descriptors and package descriptors are skipped.
     */
    Set<String> classesUnder(String prefix) {
        String directory = prefix.replace('.', '/') + "/";
        Set<String> names = new TreeSet<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                scanDirectory(root, directory, names);
            } else {
                scanJar(root, directory, names);
            }
        }
        return names;
    }

    /** Whether any root contains the package {@code prefix} itself or a subpackage of it. */
    boolean hasPackage(String prefix) {
        String directory = prefix.replace('.', '/') + "/";
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                if (Files.isDirectory(root.resolve(directory))) {
                    return true;
                }
            } else if (jarHasPackage(root, directory)) {
                return true;
            }
        }
        return false;
    }

    private static void scanDirectory(Path root, String directory, Set<String> names) {
        Path start = root.resolve(directory);
        if (!Files.isDirectory(start)) {
            return;
        }
        try (Stream<Path> files = Files.walk(start)) {
            files.filter(Files::isRegularFile)
                    .map(file -> root.relativize(file).toString().replace(File.separatorChar, '/'))
                    .forEach(relative -> addClass(relative, names));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan " + start, e);
        }
    }

    private static void scanJar(Path jar, String directory, Set<String> names) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith(directory)) {
                    addClass(entry.getName(), names);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan " + jar, e);
        }
    }

    private static boolean jarHasPackage(Path jar, String directory) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().startsWith(directory)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan " + jar, e);
        }
    }

    private static void addClass(String relativePath, Set<String> names) {
        if (!relativePath.endsWith(".class") || relativePath.startsWith("META-INF/")) {
            return;
        }
        String name = relativePath.substring(0, relativePath.length() - ".class".length());
        String simpleName = name.substring(name.lastIndexOf('/') + 1);
        if (simpleName.equals("module-info") || simpleName.equals("package-info")) {
            return;
        }
        names.add(name.replace('/', '.'));
    }

    /**
     * The directories and jars a class loader (and its parents up to the platform loader) loads from, if discoverable.
     */
    private static List<Path> rootsOf(ClassLoader loader) {
        Set<Path> roots = new LinkedHashSet<>();
        for (ClassLoader current = loader; current != null && current != ClassLoader.getPlatformClassLoader();
                current = current.getParent()) {
            if (current instanceof URLClassLoader urlLoader) {
                for (URL url : urlLoader.getURLs()) {
                    toPath(url).ifPresent(roots::add);
                }
            } else if (current == ClassLoader.getSystemClassLoader()) {
                // The application class loader is not a URLClassLoader since Java 9; its class path is the system
                // property.
                String classPath = System.getProperty("java.class.path", "");
                for (String entry : classPath.split(File.pathSeparator)) {
                    if (!entry.isBlank()) {
                        roots.add(Paths.get(entry));
                    }
                }
            }
        }
        roots.removeIf(root -> !Files.exists(root));
        return new ArrayList<>(roots);
    }

    private static java.util.Optional<Path> toPath(URL url) {
        if (!url.getProtocol().equals("file")) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Paths.get(url.toURI()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
