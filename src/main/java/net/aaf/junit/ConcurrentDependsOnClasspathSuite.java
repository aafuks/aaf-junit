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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.extensions.cpsuite.ClasspathSuite;
import org.junit.internal.builders.IgnoredClassRunner;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;
import org.junit.runners.model.RunnerScheduler;

/**
 * @author Amihai Fuks
 * @version Dec 9, 2016
 * @since 2.0.0
 */
public class ConcurrentDependsOnClasspathSuite extends ClasspathSuite {

    private static final int CORE_POOL_SIZE = 2;

    private final ConcurrentDependsOnSuiteScheduler scheduler;
    private final SuiteRunListener listener;
    private volatile RunNotifier notifier;
    private final Set<String> invoked = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> started = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> scheduled = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> finished = Collections.synchronizedSet(new HashSet<>());
    private final Set<Runner> waiting = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Runner> nameToRunner = new HashMap<>();
    private final Set<String> shouldRun = Collections.synchronizedSet(new HashSet<>());

    public ConcurrentDependsOnClasspathSuite(Class<?> suiteClass, RunnerBuilder builder) throws InitializationError {
        super(suiteClass, builder);
        int maximumPoolSize = suiteClass.isAnnotationPresent(Concurrency.class) ? suiteClass.getAnnotation(Concurrency.class).maximumPoolSize() : 0;
        listener = new SuiteRunListener();
        // +1 for controller thread (background scheduler)
        scheduler = new ConcurrentDependsOnSuiteScheduler(Math.max(CORE_POOL_SIZE, maximumPoolSize + 1), listener);
        setScheduler(scheduler);
        shouldRun();
        buildRunnerTree();
    }

    private void shouldRun() {
        for (Runner runner : getChildren()) {
            shouldRun.add(getClassName(runner));
        }
    }

    private void buildRunnerTree() {
        for (Runner runner : getChildren()) {
            nameToRunner.put(getClassName(runner), runner);
            if (runner instanceof IgnoredClassRunner) {
                failed.add(getClassName(runner));
                finished.add(getClassName(runner));
            }
        }
    }

    private static String getClassName(Runner runner) {
        return runner.getDescription().getTestClass().getName();
    }

    @Override
    protected void runChild(Runner runner, @SuppressWarnings("hiding") RunNotifier notifier) {
        if (shouldWaitAndWait(runner)) {
            scheduleDependsOnClasses(runner);
        } else if (alreadyInvoked(runner)) {
            return;
        } else if (shouldIgnore(runner)) {
            super.runChild(scheduler.newClassRunner(getClassName(runner), new IgnoredClassRunner(runner.getDescription().getTestClass())), notifier);
            runner.getDescription().getChildren().stream().forEach(t -> notifier.fireTestIgnored(t));
            failed.add(getClassName(runner));
            finished.add(getClassName(runner));
            synchronized (waiting) {
                waiting.notify();
            }
        } else {
            if (runner.getClass() != ConcurrentDependsOnSuiteScheduler.ClassRunner.class) {
                super.runChild(scheduler.newClassRunner(getClassName(runner), runner), notifier);
            } else {
                scheduler.schedule(scheduler.newClassChildStatement(getClassName(runner), () -> runChild(runner, notifier)));
            }
        }
    }

    private void scheduleDependsOnClasses(Runner runner) {
        for (String dependsOn : getDependsOnClasses(runner)) {
            if (scheduled.add(dependsOn)) {
                scheduler.schedule(scheduler.newClassChildStatement(getClassName(runner), () -> runChild(nameToRunner.get(dependsOn), notifier)));
            }
        }
    }

    private boolean alreadyInvoked(Runner runner) {
        return !invoked.add(getClassName(runner));
    }

    private boolean shouldWaitAndWait(Runner runner) {
        String[] classes = getDependsOnClasses(runner);
        synchronized (waiting) {
            boolean wait = Stream.of(classes).anyMatch(c -> !finished.contains(c));
            if (wait) {
                waiting.add(runner);
            }
            return wait;
        }
    }

    private boolean shouldIgnore(Runner runner) {
        String[] classes = getDependsOnClasses(runner);
        return Stream.of(classes).anyMatch(c -> failed.contains(c));
    }

    private static String[] getDependsOnClasses(Runner runner) {
        if (!runner.getDescription().getTestClass().isAnnotationPresent(DependsOn.class)) {
            return new String[0];
        }
        DependsOn dependsOn = runner.getDescription().getTestClass().getAnnotation(DependsOn.class);
        return Arrays.stream(dependsOn.classes()).map(c -> c.getName()).collect(Collectors.toList()).toArray(new String[0]);
    }

    @Override
    public void run(@SuppressWarnings("hiding") RunNotifier notifier) {
        this.notifier = notifier;
        this.notifier.addListener(listener);
        scheduler.schedule(newBackgroundSchedulerThread());
        super.run(this.notifier);
    }

    @Override
    public void filter(Filter filter) throws NoTestsRemainException {
        RecordingFilter outer = new RecordingFilter(filter);
        super.filter(outer);
        shouldRun.removeAll(outer.getShouldNotRun());
    }

    private static class RecordingFilter extends Filter {

        private final Filter inner;
        private final Set<String> shouldNotRun;

        public RecordingFilter(Filter inner) {
            this.inner = inner;
            shouldNotRun = new HashSet<>();
        }

        @Override
        public boolean shouldRun(Description description) {
            if (inner.shouldRun(description)) {
                return true;
            } else {
                shouldNotRun.add(description.getMethodName());
                return false;
            }
        }

        public Set<String> getShouldNotRun() {
            return shouldNotRun;
        }

        @Override
        public String describe() {
            return inner.describe();
        }

        @Override
        public void apply(Object child) throws NoTestsRemainException {
            inner.apply(child);
        }

        @Override
        public Filter intersect(Filter second) {
            return inner.intersect(second);
        }

    }

    private Runnable newBackgroundSchedulerThread() {
        return scheduler.newClassChildStatement("bg-scheduler", () -> {
            while (true) {
                synchronized (waiting) {
                    if (waiting.isEmpty() && finished.containsAll(shouldRun)) {
                        return;
                    }
                    for (Iterator<Runner> iter = waiting.iterator(); iter.hasNext();) {
                        Runner runner = iter.next();
                        if (!shouldWait(runner)) {
                            iter.remove();
                            scheduler.schedule(scheduler.newClassChildStatement(getClassName(runner), () -> runChild(runner, notifier)));
                        }
                    }
                    try {
                        waiting.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
    }

    private boolean shouldWait(Runner runner) {
        String[] classes = getDependsOnClasses(runner);
        return Stream.of(classes).anyMatch(m -> !finished.contains(m));
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

        void classFinished(String className) {
            synchronized (waiting) {
                if (started.contains(className)) {
                    finished.add(className);
                    waiting.notify();
                }
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
