aaf-junit
=========

aaf-junit is an extention to the junit f/w that enables test dependency within a single test class.

Hello example
```java
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(ConcurrentDependsOnRunner.class)
@Concurrency(maximumPoolSize = 10)
public class Test {

    @BeforeClass
    public static void beforeClass() {
        System.out.println("beforeClass");
    }

    @Before
    public void before() {
        System.out.println("before");
    }

    @Test
    @DependsOn(tests = { "test3" })
    public void test1() throws Exception {
        System.out.println("test1");
    }

    @Test
    @DependsOn(tests = { "test1" })
    public void test2() throws Exception {
        System.out.println("test2");
    }

    @Test
    public void test3() throws Exception {
        System.out.println("test3");
    }

    @Test
    @DependsOn(tests = { "test1" })
    public void test4() throws Exception {
        System.out.println("test4");
    }

    @After
    public void after() {
        System.out.println("after");
    }

    @AfterClass
    public static void afterClass() {
        System.out.println("afterClass");
    }

}