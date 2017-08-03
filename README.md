## The problem

The problem with Hibernate and CockroachDB that this solves is that Hibernate
does not support `SAVEPOINT`.
This is a problem, because Cockroach [ab]uses the SQL syntax for `SAVEPOINT` to
allow for transaction retries.

The core problem comes down to the fact that Hibernate assumes that once a
transaction has failed, it will not be started again, whereas in Cockroach we
can run `ROLLBACK TO SAVEPOINT...` to retry.

The fix is to replace the Hibernate class that disallows this with one that
allows it.

## What to do

There are two files in this repo, `CockroachDBTransactionCoordinator.java`, and
`CockroachDBTransactionCoordinatorBuilder.java` which can be included in a
Hibernate project to accomplish this.

To install them, but them in the repo under a path like
`src/main/java/com/cockroachlabs` and add the following config to the
`hibernate.cfg.xml`:

```xml
<!-- Replace the existing TransactionCoordinator with the CockroachDB-compatible one -->
<property name="hibernate.transaction.coordinator_class">com.cockroachlabs.CockroachDBTransactionCoordinatorBuilder</property>
```

Then they should be able to successfully run queries like

```java
session.createNativeQuery("ROLLBACK TO SAVEPOINT COCKROACH_RESTART").executeUpdate();
```

in the case of an error.
