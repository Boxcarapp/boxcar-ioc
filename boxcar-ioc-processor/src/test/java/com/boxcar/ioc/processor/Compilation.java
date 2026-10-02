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

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Runs javac in-process on in-memory sources, optionally with the {@link InjectorProcessor}. */
final class Compilation {

    record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path classes, Path sources) {

        List<Diagnostic<? extends JavaFileObject>> ofKind(Diagnostic.Kind kind) {
            return diagnostics.stream().filter(d -> d.getKind() == kind).toList();
        }

        List<String> messages(Diagnostic.Kind kind) {
            return ofKind(kind).stream().map(d -> d.getMessage(null)).toList();
        }

        boolean hasMessage(Diagnostic.Kind kind, String fragment) {
            return messages(kind).stream().anyMatch(m -> m.contains(fragment));
        }

        String generatedInjector() throws IOException {
            Path file = sources.resolve(InjectorGenerator.QUALIFIED_NAME.replace('.', '/') + ".java");
            return Files.exists(file) ? Files.readString(file) : null;
        }
    }

    private Compilation() {
    }

    /** Compiles {@code sources} (qualified class name to source text) with the processor into {@code workDir}. */
    static Result withProcessor(Path workDir, Map<String, String> sources, String... extraOptions) throws IOException {
        return run(workDir, sources, "-proc:only", List.of(), List.of(extraOptions));
    }

    static Result withoutProcessor(Path workDir, Map<String, String> sources) throws IOException {
        return run(workDir, sources, "-proc:none", List.of(), List.of());
    }

    /** Like {@link #withProcessor} but also compiles the sources and the generated injector to class files. */
    static Result compileWithProcessor(Path workDir, Map<String, String> sources, List<Path> extraClasspath,
            String... extraOptions) throws IOException {
        return run(workDir, sources, "-proc:full", extraClasspath, List.of(extraOptions), null);
    }

    /**
     * Like {@link #compileWithProcessor}, but the processor is loaded through a class loader whose roots are
     * {@code processorLoaderRoots} plus the processor's own classes, which is what the processor sees when it is
     * on the compile class path rather than on a separate processor path.
     */
    static Result compileWithProcessorLoadedFrom(Path workDir, List<Path> processorLoaderRoots,
            Map<String, String> sources, List<Path> extraClasspath, String... extraOptions) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (Path root : processorLoaderRoots) {
            urls.add(root.toUri().toURL());
        }
        urls.add(InjectorProcessor.class.getProtectionDomain().getCodeSource().getLocation());
        // Parent is the platform loader so that the processor classes really come from this loader.
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Processor processor = (Processor) loader.loadClass(InjectorProcessor.class.getName())
                    .getConstructor().newInstance();
            return run(workDir, sources, "-proc:full", extraClasspath, List.of(extraOptions), processor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Result run(Path workDir, Map<String, String> sources, String procOption, List<Path> extraClasspath,
            List<String> extraOptions) throws IOException {
        return run(workDir, sources, procOption, extraClasspath, extraOptions, null);
    }

    private static Result run(Path workDir, Map<String, String> sources, String procOption, List<Path> extraClasspath,
            List<String> extraOptions, Processor processor) throws IOException {
        boolean processing = !procOption.equals("-proc:none");
        Path classes = Files.createDirectories(workDir.resolve("classes"));
        Path generated = Files.createDirectories(workDir.resolve("generated"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        String classpath = Stream.concat(extraClasspath.stream().map(Path::toString),
                Stream.of(System.getProperty("java.class.path")))
                .reduce((a, b) -> a + java.io.File.pathSeparator + b).orElse("");

        List<String> options = new ArrayList<>(List.of(
                "-d", classes.toString(),
                "-s", generated.toString(),
                "-classpath", classpath,
                "-implicit:none"));
        options.add(procOption);
        options.addAll(extraOptions);

        List<JavaFileObject> units = sources.entrySet().stream()
                .map(e -> (JavaFileObject) new Source(e.getKey(), e.getValue()))
                .toList();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(collector, null,
                StandardCharsets.UTF_8)) {
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, collector, options, null, units);
            if (processing) {
                task.setProcessors(List.of(processor != null ? processor : new InjectorProcessor()));
            }
            boolean success = task.call();
            return new Result(success, collector.getDiagnostics(), classes, generated);
        }
    }

    private static final class Source extends SimpleJavaFileObject {

        private final String content;

        Source(String qualifiedName, String content) {
            super(URI.create("string:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
