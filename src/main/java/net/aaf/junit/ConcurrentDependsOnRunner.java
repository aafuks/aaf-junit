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
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.runner.Description;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerScheduler;

/**
 * @author Amihai Fuks
 * @version Apr 24, 2016
 * @since 1.0.0
 */
public class ConcurrentDependsOnRunner extends BlockJUnit4ClassRunner {

    private volatile RunNotifier notifier;

    private final ConcurrentDependsOnRunnerScheduler scheduler;
    private final DependencyGraph graph = new DependencyGraph();
    private final Set<String> invoked = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> finished = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, FrameworkMethod> nameToMethod = new HashMap<>();
    private final Set<String> shouldRun = Collections.synchronizedSet(new HashSet<>());

    public ConcurrentDependsOnRunner(Class<?> klass) throws InitializationError {
        super(klass);
        int maximumPoolSize = isAnnotationPresent(klass) && !sysPropEquals("dependson.runner.serial") ? maximumPoolSize(klass)
                : 1;
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize < 1");
        }
        scheduler = new ConcurrentDependsOnRunnerScheduler(maximumPoolSize);
        setScheduler(scheduler);
        getChildren().stream().forEach(m -> shouldRun.add(getName(m)));
        getChildren().stream().forEach(m -> nameToMethod.put(getName(m), m));
        getChildren().stream().forEach(m -> graph.addDependecy(getName(m), getDependsOnTests(m)));
        graph.verify();
        if (sysPropEquals("dependency.graph.print")) {
            System.out.println(graph.toString());
        }
    }

    private static boolean isAnnotationPresent(Class<?> klass) {
        return klass.isAnnotationPresent(Concurrency.class);
    }

    private static int maximumPoolSize(Class<?> klass) {
        return klass.getAnnotation(Concurrency.class).maximumPoolSize();
    }

    private String getName(FrameworkMethod method) {
        return getName(method.getName());
    }

    private static String getName(Description description) {
        return getName(description.getClassName(), description.getMethodName());
    }

    private String getName(String methodName) {
        return getName(getTestClass().getName(), methodName);
    }

    private static String getName(String className, String methodName) {
        return className + "#" + methodName;
    }

    @Override
    protected void runChild(FrameworkMethod method, @SuppressWarnings("hiding") RunNotifier notifier) {
        if (shouldWait(method)) {
            scheduleDependsOnTests(method);
        } else if (alreadyInvoked(method)) {
            return;
        } else if (shouldIgnore(method)) {
            notifier.fireTestIgnored(describeChild(method));
        } else {
            super.runChild(method, notifier);
        }
    }

    private void scheduleDependsOnTests(FrameworkMethod method) {
        for (String test : getDependsOnTests(method)) {
            shouldRun.add(test);
            scheduler.schedule(() -> runChild(nameToMethod.get(test), notifier));
        }
    }

    private boolean alreadyInvoked(FrameworkMethod method) {
        return !invoked.add(getName(method));
    }

    private boolean shouldIgnore(FrameworkMethod method) {
        return Arrays.stream(getDependsOnTests(method)).anyMatch(m -> failed.contains(m)) || !enabledWith(method);
    }

    private static boolean enabledWith(FrameworkMethod method) {
        EnabledWith enabledWith = method.getAnnotation(EnabledWith.class);
        return enabledWith == null || sysPropEquals(enabledWith.value());
    }

    private static boolean sysPropEquals(String prop) {
        int equalIdx = prop.indexOf('=');
        if (equalIdx == -1 || equalIdx == prop.length() - 1) {
            String key = prop.substring(0, equalIdx != -1 ? equalIdx : prop.length());
            return System.getProperty(key) != null;
        } else {
            String key = prop.substring(0, equalIdx);
            String value = prop.substring(equalIdx + 1);
            return value.equalsIgnoreCase(System.getProperty(key));
        }
    }

    private String[] getDependsOnTests(FrameworkMethod method) {
        if (!method.getMethod().isAnnotationPresent(DependsOnTests.class)) {
            return new String[0];
        }
        DependsOnTests dependsOn = method.getMethod().getAnnotation(DependsOnTests.class);
        return Arrays.stream(dependsOn.value()).map(t -> getName(t)).collect(Collectors.toList()).toArray(new String[0]);
    }

    @Override
    public void run(@SuppressWarnings("hiding") RunNotifier notifier) {
        this.notifier = notifier;
        this.notifier.addListener(newRunListener());
        super.run(this.notifier);
    }

    @Override
    public void filter(Filter filter) throws NoTestsRemainException {
        RecordingFilter outer = new RecordingFilter(filter, d -> getName(d));
        super.filter(outer);
        shouldRun.retainAll(outer.getShouldRun());
    }

    private boolean shouldWait(FrameworkMethod method) {
        return Arrays.stream(getDependsOnTests(method)).anyMatch(m -> !finished.contains(m));
    }

    private RunListener newRunListener() {
        return new RunListener() {
            @Override
            public void testFailure(Failure failure) throws Exception {
                failed.add(getName(failure.getDescription()));
            }

            @Override
            public void testAssumptionFailure(Failure failure) {
                failed.add(getName(failure.getDescription()));
            }

            @Override
            public void testIgnored(Description description) throws Exception {
                failed.add(getName(description));
                scheduleOnDepends(description);
            }

            @Override
            public void testFinished(Description description) throws Exception {
                scheduleOnDepends(description);
            }

            private void scheduleOnDepends(Description description) {
                if (finished.add(getName(description))) {
                    graph.next(getName(description)).stream().filter(t -> shouldRun.contains(t))
                    .forEach(t -> {
                        scheduler.schedule(() -> runChild(nameToMethod.get(t), notifier));
                    });
                }
            }
        };
    }

    private class RecordingFilter extends Filter {

        private final Filter inner;
        private final Function<Description, String> nameResolver;
        @SuppressWarnings("hiding")
        private final Set<String> shouldRun;

        private RecordingFilter(Filter inner, Function<Description, String> nameResolver) {
            this.inner = inner;
            this.nameResolver = nameResolver;
            shouldRun = new HashSet<>();
        }

        public Set<String> getShouldRun() {
            return shouldRun;
        }

        @Override
        public boolean shouldRun(Description description) {
            if (inner.shouldRun(description)) {
                shouldRun.add(nameResolver.apply(description));
                return true;
            } else {
                return false;
            }
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

    private static class ConcurrentDependsOnRunnerScheduler implements RunnerScheduler {

        private final AtomicInteger tests = new AtomicInteger();

        private final ExecutorService executorService;
        private final CompletionService<Void> completionService;

        private ConcurrentDependsOnRunnerScheduler(int maximumPoolSize) {
            executorService = Executors.newFixedThreadPool(maximumPoolSize);
            completionService = new ExecutorCompletionService<>(executorService);
        }

        @Override
        public void schedule(Runnable childStatement) {
            tests.incrementAndGet();
            completionService.submit(childStatement, null);
        }

        @Override
        public void finished() {
            try {
                while (tests.get() != 0) {
                    completionService.take();
                    tests.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                executorService.shutdownNow();
            }
        }

    }

}