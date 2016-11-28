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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.runner.Description;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;

/**
 * @author Amihai Fuks
 * @version Apr 24, 2016
 * @since 1.0.0
 */
public class ConcurrentDependsOnRunner extends BlockJUnit4ClassRunner {

    private static final int CORE_POOL_SIZE = 2;

    private final ConcurrentDependsOnRunnerScheduler scheduler;
    private volatile RunNotifier notifier;
    private final Set<String> invoked = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> scheduled = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> finished = Collections.synchronizedSet(new HashSet<>());
    private final Set<FrameworkMethod> waiting = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, FrameworkMethod> nameToMethod = new HashMap<>();
    private final Set<String> shouldRun = Collections.synchronizedSet(new HashSet<>());

    public ConcurrentDependsOnRunner(Class<?> klass) throws InitializationError {
        super(klass);
        int maximumPoolSize = klass.isAnnotationPresent(Concurrency.class) ? klass.getAnnotation(Concurrency.class).maximumPoolSize() : 0;
        // +1 for controller thread (background scheduler)
        scheduler = new ConcurrentDependsOnRunnerScheduler(Math.max(CORE_POOL_SIZE, maximumPoolSize + 1));
        setScheduler(scheduler);
        shouldRun();
        buildFrameworkMethodTree();
    }

    private void shouldRun() {
        for (FrameworkMethod method : getChildren()) {
            shouldRun.add(method.getName());
        }
    }

    private void buildFrameworkMethodTree() {
        for (FrameworkMethod method : getChildren()) {
            nameToMethod.put(method.getName(), method);
        }
    }

    @Override
    protected void runChild(FrameworkMethod method, @SuppressWarnings("hiding") RunNotifier notifier) {
        if (shouldWaitAndWait(method)) {
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
        for (String dependsOn : getDependsOnTests(method)) {
            if (scheduled.add(dependsOn)) {
                scheduler.schedule(() -> ConcurrentDependsOnRunner.this.runChild(nameToMethod.get(dependsOn), notifier));
            }
        }
    }

    private boolean alreadyInvoked(FrameworkMethod method) {
        return !invoked.add(method.getName());
    }

    private boolean shouldWaitAndWait(FrameworkMethod method) {
        String[] tests = getDependsOnTests(method);
        synchronized (waiting) {
            boolean wait = Stream.of(tests).anyMatch(m -> !finished.contains(m));
            if (wait) {
                waiting.add(method);
            }
            return wait;
        }
    }

    private boolean shouldIgnore(FrameworkMethod method) {
        String[] tests = getDependsOnTests(method);
        synchronized (failed) {
            return Stream.of(tests).anyMatch(m -> failed.contains(m));
        }
    }

    private static String[] getDependsOnTests(FrameworkMethod method) {
        if (!method.getMethod().isAnnotationPresent(DependsOn.class)) {
            return new String[0];
        }
        DependsOn dependsOn = method.getMethod().getAnnotation(DependsOn.class);
        return dependsOn.tests();
    }

    @Override
    public void run(@SuppressWarnings("hiding") RunNotifier notifier) {
        this.notifier = notifier;
        this.notifier.addListener(newRunListener());
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
        return () -> {
            while (true) {
                synchronized (waiting) {
                    if (waiting.isEmpty() && finished.containsAll(shouldRun)) {
                        return;
                    }
                    for (Iterator<FrameworkMethod> iter = waiting.iterator(); iter.hasNext();) {
                        FrameworkMethod method = iter.next();
                        if (!shouldWait(method)) {
                            iter.remove();
                            scheduler.schedule(() -> {
                                ConcurrentDependsOnRunner.this.runChild(method, notifier);
                            });
                        }
                    }
                    try {
                        waiting.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        };
    }

    private boolean shouldWait(FrameworkMethod method) {
        String[] tests = getDependsOnTests(method);
        synchronized (waiting) {
            return Stream.of(tests).anyMatch(m -> !finished.contains(m));
        }
    }

    private RunListener newRunListener() {
        return new RunListener() {
            @Override
            public void testFailure(Failure failure) throws Exception {
                failed.add(failure.getDescription().getMethodName());
            }

            @Override
            public void testAssumptionFailure(Failure failure) {
                failed.add(failure.getDescription().getMethodName());
            }

            @Override
            public void testIgnored(Description description) throws Exception {
                failed.add(description.getMethodName());
                notifyBackgroudThread(description);
            }

            @Override
            public void testFinished(Description description) throws Exception {
                notifyBackgroudThread(description);
            }

            private void notifyBackgroudThread(Description description) {
                synchronized (waiting) {
                    finished.add(description.getMethodName());
                    waiting.notifyAll();
                }
            }
        };
    }

}