# Persistence

CQEngine supports on-heap, off-heap and disk-backed collections. Off-heap and disk persistence use SQLite, while
stored objects are serialized through the `PojoSerializer` selected by `@PersistenceConfig`. This guide describes the
transaction, locking, naming and serialization contracts which matter when a collection is durable.

CQEngine obtains sqlite-jdbc through its declared dependency. Runtime
qualification extracts and loads only the native selected for the current OS and architecture, verifies the extracted
bytes and executes SQLite integrity, version and compile-option queries. Evidence never treats checksum validation of
another platform's binary as proof that binary was loaded.

## Request transactions

Each CQEngine collection operation opens a request scope in a rollback state. Built-in disk and off-heap persistence
commit only after the object store and every index complete normally; an exception rolls the request back. Closing a
request is therefore not treated as evidence that it succeeded.

Collection iterators keep their read request open until the iterator closes. A successful `Iterator.remove()` commits
that removal while leaving the cursor open. If later iterator work fails, CQEngine rolls back work performed since the
last successful removal and closes the cursor and request scope.

`TransactionalIndexedCollection` also restores an empty MVCC exclusion version after a failed update. Persistence is
rolled back before that empty version becomes visible, and the writer waits for readers of the failed exclusion version
to finish. See [Transaction Isolation](TransactionIsolation.md) for the collection-level MVCC model.

Third-party `Persistence` implementations remain source compatible through the original
`closeRequestScopeResources(QueryOptions)` method. A transactional implementation should additionally override the
outcome-aware close method. An implementation which supports mutating iterators should implement the request-scope
commit hook so that a successful removal becomes visible before the iterator closes.

### Composite persistence

`CompositePersistence` can enlist more than one independent SQLite database, but it does not provide a distributed
transaction or recovery log. Connections commit sequentially. If a later commit fails, an earlier successful commit
cannot be undone; CQEngine rolls back the failing and not-yet-committed connections, closes every connection and
reports the failure.

Applications which require atomic durability should keep all participating SQLite indexes in one persistence
transaction domain. A composite spanning independent databases provides coordinated cleanup, not distributed
atomicity.

## SQLite lock waits

Disk persistence waits up to 3,000 milliseconds by default when another connection holds a conflicting database lock.
This is an explicit CQEngine default and matches sqlite-jdbc 3.53.2.1; it does not inherit an unbounded driver value.

Override `busy_timeout` through `DiskPersistence.onPrimaryKeyInFileWithProperties(...)`. The value is an integer
number of milliseconds from `0` through `Integer.MAX_VALUE`:

- `0` reports a conflicting lock immediately;
- a positive value bounds the SQLite lock wait; and
- a negative, empty, non-numeric or out-of-range value fails during persistence construction.

`DiskPersistence.getBusyTimeoutMillis()` returns the effective value. The timeout bounds SQLite's lock wait, not the
whole application operation, so thread scheduling can add a small amount of elapsed time.

CQEngine begins collection mutation requests with `BEGIN IMMEDIATE` when the configured transaction mode would
otherwise be `DEFERRED`. Acquiring SQLite's single-writer slot before schema and index reads lets the configured busy
handler govern writer contention. Caller-selected `IMMEDIATE` and `EXCLUSIVE` modes are retained. Read-only access,
query retrieval and metadata access are classified as reads; iterator requests remain capable of
`Iterator.remove()`.

Base and extended `SQLITE_BUSY` failures are exposed as `SQLiteBusyException`, an `IllegalStateException` which
provides the primary error code, exact driver error code and original sqlite-jdbc `SQLiteException`. A busy failure
means the operation did not complete. Let its request scope roll back, then retry the complete application operation
according to a bounded application policy.

## SQLite identifiers

CQEngine constructs its table and index names; callers do not supply complete SQL identifiers. Version-two names use
the form `cqtbl_v2_<sha256>` and `cqidx_v2_<sha256>_value`. The SHA-256 input is a domain-separated,
length-delimited representation of the Java attribute name and suffix. It therefore distinguishes names such as
`a-b` and `ab`, as well as attribute/suffix pairs which would be ambiguous if concatenated. Partial indexes derive
their suffix from the unsanitized filter description before the table identity is hashed.

Every generated component passed to the SQLite query layer is non-empty, no more than 255 characters and matches
`[A-Za-z0-9_]+`. A public `SQLiteIndex` suffix uses the same alphabet and length bound, except that the empty suffix
remains valid for the standard factories. Generated names are delimited with SQLite double quotes. Invalid suffixes
and internal components are rejected before JDBC interaction, and validation messages do not echo rejected text.

### Opening a legacy database

On first index initialization, CQEngine checks for the table produced by the historical
`sanitizeForTableName(String)` mapping. If it exists and its V2 table does not, CQEngine atomically:

1. renames the table to its V2 name;
2. replaces the legacy value-index name with the V2 index name; and
3. records the legacy-to-V2 assignment in `cqengine_sqlite_identifier_migrations_v2`.

Subsequent openings verify that record and the schema agree. If two distinct logical names share one legacy
sanitizer output, the recorded assignment prevents the later index from claiming the migrated data; it receives its
own V2 table and is populated from the object store. If both legacy and V2 tables already exist, or migration metadata
disagrees with the schema, initialization fails rather than choosing one and risking silent data loss.

A legacy schema cannot reveal which original name produced a sanitized component. Inventory potentially colliding
attribute names and partial filters before the first upgraded opening, and initialize the intended owner of each
legacy table first. Take a database backup before upgrade. The rename is transactional, but older CQEngine versions
do not understand V2 names, so do not let old and upgraded processes open the same database. Rollback requires
restoring the pre-upgrade backup. An ambiguous or already-shared legacy index should be rebuilt from the authoritative
object store instead of being assigned by assumption.

## Kryo serialization

CQEngine uses Kryo 5.6.2 with reference tracking enabled. CQEngine's serializers for `Arrays.asList` and the
`Collections.unmodifiable*` and `Collections.synchronized*` wrapper families use public JDK APIs and require no module
openings on Java 21 or Java 25. Existing Kryo 5.0.0-RC1 object blobs and an indexed SQLite database produced by the
historical CQEngine stack are part of the compatibility corpus.

Old bytes retain their recorded array component type when read. New `Arrays.asList` writes store an `Object[]` value
snapshot because the public `List` API does not expose the backing array type. Wrapper writes retain values, ordering,
comparators, wrapper category, synchronization and immutability, but do not preserve hidden backing identity,
implementation class or cycles through a hidden backing collection. Wrapper kind and registration order are part of
the stored format and remain stable.

CQEngine initializes Kryo in safe mode through the documented `kryo.unsafe=false` setting. An explicitly configured
different value is rejected. If another library initializes the same Kryo copy with Unsafe first, persistence is also
rejected; start that process with `-Dkryo.unsafe=false`.

Kryo instances are not shared concurrently. Platform threads retain the historical per-thread cache. Virtual threads
on Java 21 and later borrow instances from a reusable pool capped between 2 and 16 instances according to the available
processors; callers wait when that bound is reached, and each instance is reset before reuse. This avoids retaining one
Kryo object graph per short-lived virtual thread while keeping the existing platform-thread extension behavior.

### Deserialization modes

`PersistenceConfig.deserializationMode` makes the store trust decision explicit:

| Mode | Stored format | Class policy | Appropriate use |
|---|---|---|---|
| `TRUSTED_STORE_COMPATIBILITY` | Historical raw Kryo bytes | Unregistered class names remain enabled | Existing stores whose database, backups and restore path are protected from untrusted modification |
| `REGISTERED_TYPES` | CQEngine envelope version 1 | The root, framework types and application `allowedTypes` must be registered | New stores with a reviewed object-graph allowlist |

Compatibility mode remains the default so an upgrade does not silently make an existing store unreadable. In
polymorphic compatibility mode, bytes can name a class available on the application classpath. Treat the database,
backups and restore path as trusted input.

Registered-types mode normalizes application registrations by binary class name and rejects anonymous, local and
synthetic classes. The versioned envelope records the format, polymorphism setting, registration fingerprint and exact
payload length. It detects a reader/configuration mismatch and trailing or truncated data; it is not a signature or
MAC and does not authenticate the bytes. Registered mode deliberately rejects raw legacy bytes instead of falling
back to class-name deserialization.

For example, a new store can declare the concrete application types which may occur in its object graph:

```java
@PersistenceConfig(
        deserializationMode = KryoDeserializationMode.REGISTERED_TYPES,
        allowedTypes = {Address.class, AccountState.class})
public final class Customer {
}
```

The root type and CQEngine's standard registered types do not need to be repeated in `allowedTypes`. Application value
classes, enum types, comparators and other concrete graph types do.

### Finite limits

Both modes apply finite values from `@PersistenceConfig` before using Kryo:

| Setting | Default | Scope |
|---|---:|---|
| `maxSerializedBytes` | 16 MiB | Complete serialized input or output, including a registered-mode envelope |
| `maxGraphDepth` | 100 | Kryo object-graph depth |
| `maxContainerElements` | 1,000,000 | CQEngine-owned wrapper serializers in both modes; registered arrays and common collections/maps |
| `maxStringCharacters` | 1,000,000 | UTF-16 characters in Kryo strings |

Every value must be positive. Output uses a bounded buffer, input length is checked before decoding, string lengths are
checked before buffer growth, and the complete payload must be consumed.

Registered mode checks arrays, common mutable collections and maps, and the specialized runtime types returned by
`List.of`, `Set.of` and `Map.of` before allocating their backing containers. CQEngine's `Arrays.asList` and
unmodifiable/synchronized wrapper serializers apply the same bound in both modes. The limit is not universal:
compatibility mode retains historical serializer selection for other unregistered containers, and custom or
third-party serializers can allocate from their own encoded lengths. Those serializers require separate review.

### Migrating an existing store

There is no automatic dual reader. To adopt registered-types mode, read a protected existing store in compatibility
mode, validate its objects, then rewrite it in registered mode as a controlled migration. Registered-mode bytes are not
readable by the historical CQEngine/Kryo stack. Keep a database snapshot or a tested reverse-migration procedure for
rollback.

Neither mode is a hostile-deserialization sandbox. If an adversary can supply store bytes, combine registered-types
mode with authenticated storage and a process-level memory and time boundary.
