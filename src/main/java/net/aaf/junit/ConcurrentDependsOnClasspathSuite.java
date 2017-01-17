/*
 * Licensed under the MIT license (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.aaf.junit;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.extensions.cpsuite.ClasspathSuite;
import org.junit.internal.builders.IgnoredClassRunner;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;
import org.junit.runners.model.RunnerScheduler;

/**
 * @author Amihai Fuks
 * @version Dec 9, 2016
 * @since 1.0.0
 */
public class ConcurrentDependsOnClasspathSuite extends ClasspathSuite {

    private volatile RunNotifier notifier;

    private final SuiteRunListener listener = new SuiteRunListener();
    private final DependencyTree tree = new DependencyTree();
    private final ConcurrentDependsOnSuiteScheduler scheduler;
    private final Set<String> invoked = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> started = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> finished = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Runner> nameToRunner = new HashMap<>();
    private final Set<String> shouldRun = Collections.synchronizedSet(new HashSet<>());

    public ConcurrentDependsOnClasspathSuite(Class<?> suiteClass, RunnerBuilder builder) throws InitializationError {
        super(suiteClass, builder);
        int maximumPoolSize = isAnnotationPresent(suiteClass) && !"true".equals(System.getProperty("dependson.suite.serial")) ? maximumPoolSize(suiteClass)
                : 1;
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize < 1");
        }
        scheduler = new ConcurrentDependsOnSuiteScheduler(maximumPoolSize, listener);
        setScheduler(scheduler);
        getChildren().stream().forEach(r -> shouldRun.add(getClassName(r)));
        getChildren().stream().forEach(r -> nameToRunner.put(getClassName(r), r));
        getChildren().stream().forEach(r -> tree.addDependecy(getClassName(r), getDependsOnClasses(r)));
        tree.verify();
        getChildren().stream().filter(r -> r instanceof IgnoredClassRunner).forEach(r -> {
            failed.add(getClassName(r));
            finished.add(getClassName(r));
        });
    }

    private static boolean isAnnotationPresent(Class<?> suiteClass) {
        return suiteClass.isAnnotationPresent(Concurrency.class);
    }

    private static int maximumPoolSize(Class<?> suiteClass) {
        return suiteClass.getAnnotation(Concurrency.class).maximumPoolSize();
    }

    private static String getClassName(Runner runner) {
        return runner.getDescription().getTestClass().getName();
    }

    @Override
    protected void runChild(Runner runner, @SuppressWarnings("hiding") RunNotifier notifier) {
        if (shouldWait(runner)) {
            scheduleDependsOnClasses(runner);
        } else if (alreadyInvoked(runner)) {
            return;
        } else if (shouldIgnore(runner)) {
            super.runChild(scheduler.newClassRunner(getClassName(runner), new IgnoredClassRunner(runner.getDescription().getTestClass())), notifier);
            runner.getDescription().getChildren().stream().forEach(t -> notifier.fireTestIgnored(t));
            failed.add(getClassName(runner));
            finished.add(getClassName(runner));
        } else {
            super.runChild(scheduler.newClassRunner(getClassName(runner), runner), notifier);
        }
    }

    private void scheduleDependsOnClasses(Runner runner) {
        for (String dependsOn : getDependsOnClasses(runner)) {
            shouldRun.add(getClassName(runner));
            scheduler.schedule(scheduler.newClassChildStatement(getClassName(runner), () -> runChild(nameToRunner.get(dependsOn), notifier)));
        }
    }

    private boolean alreadyInvoked(Runner runner) {
        return !invoked.add(getClassName(runner));
    }

    private boolean shouldWait(Runner runner) {
        return Arrays.stream(getDependsOnClasses(runner)).anyMatch(c -> !finished.contains(c));
    }

    private boolean shouldIgnore(Runner runner) {
        return Arrays.stream(getDependsOnClasses(runner)).anyMatch(c -> failed.contains(c));
    }

    private static String[] getDependsOnClasses(Runner runner) {
        if (!runner.getDescription().getTestClass().isAnnotationPresent(DependsOnClasses.class)) {
            return new String[0];
        }
        DependsOnClasses dependsOn = runner.getDescription().getTestClass().getAnnotation(DependsOnClasses.class);
        return Arrays.stream(dependsOn.value()).map(c -> c.getName()).collect(Collectors.toList()).toArray(new String[0]);
    }

    @Override
    public void run(@SuppressWarnings("hiding") RunNotifier notifier) {
        this.notifier = notifier;
        this.notifier.addListener(listener);
        super.run(this.notifier);
    }

    private class SuiteRunListener extends RunListener {

        @Override
        public void testFailure(Failure failure) throws Exception {
            started.add(failure.getDescription().getTestClass().getName());
            failed.add(failure.getDescription().getTestClass().getName());
        }

        @Override
        public void testAssumptionFailure(Failure failure) {
            started.add(failure.getDescription().getTestClass().getName());
            failed.add(failure.getDescription().getTestClass().getName());
        }

        @Override
        public void testIgnored(Description description) throws Exception {
            started.add(description.getTestClass().getName());
        }

        @Override
        public void testFinished(Description description) throws Exception {
            started.add(description.getTestClass().getName());
        }

        private void classFinished(String className) {
            if (started.contains(className)) {
                finished.add(className);
                tree.next(className).stream().filter(t -> shouldRun.contains(t)).forEach(t -> runChild(nameToRunner.get(t), notifier));
            }
        }

    }

    private static class ConcurrentDependsOnSuiteScheduler implements RunnerScheduler {

        private final AtomicInteger classes = new AtomicInteger();

        private final ExecutorService executorService;
        private final CompletionService<Void> completionService;

        private final SuiteRunListener listener;

        private ConcurrentDependsOnSuiteScheduler(int maximumPoolSize, SuiteRunListener listener) {
            executorService = Executors.newFixedThreadPool(maximumPoolSize);
            completionService = new ExecutorCompletionService<>(executorService);
            this.listener = listener;
        }

        @Override
        public void schedule(Runnable childStatement) {
            classes.incrementAndGet();
            completionService.submit(childStatement, null);
        }

        @Override
        public void finished() {
            try {
                while (classes.get() != 0) {
                    completionService.take();
                    classes.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                executorService.shutdownNow();
            }
        }

        private ClassRunner newClassRunner(String className, Runner r) {
            return new ClassRunner(className, r);
        }

        private ClassChildStatement newClassChildStatement(String className, Runnable r) {
            return new ClassChildStatement(className, r);
        }

        private class ClassRunner extends Runner {

            private final String className;
            private final Runner r;

            private ClassRunner(String className, Runner r) {
                this.className = className;
                this.r = r;
            }

            @Override
            public Description getDescription() {
                return r.getDescription();
            }

            @Override
            public void run(RunNotifier notifier) {
                r.run(notifier);
                listener.classFinished(className);
            }

        }

        private class ClassChildStatement implements Runnable {

            private String className;
            private Runnable r;

            private ClassChildStatement(String className, Runnable r) {
                this.className = className;
                this.r = r;
            }

            @Override
            public void run() {
                r.run();
                listener.classFinished(className);
            }

        }

    }

}
