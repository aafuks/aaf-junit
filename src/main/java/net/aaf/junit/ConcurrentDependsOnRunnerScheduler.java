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

import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.runners.model.RunnerScheduler;

/**
 * @author Amihai Fuks
 * @version Apr 24, 2016
 * @since 1.0.0
 */
class ConcurrentDependsOnRunnerScheduler implements RunnerScheduler {

    private final AtomicInteger tests = new AtomicInteger();

    private final ExecutorService executorService;
    private final CompletionService<Void> completionService;

    ConcurrentDependsOnRunnerScheduler(int maximumPoolSize) {
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