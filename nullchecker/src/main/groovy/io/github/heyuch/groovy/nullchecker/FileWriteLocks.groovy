package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/** One lock per output path, so separate sink/coverage instances cannot interleave lines. */
@CompileStatic
@PackageScope
class FileWriteLocks {

    private static final ConcurrentMap<String, Object> LOCKS = new ConcurrentHashMap<String, Object>()

    private static final Object MAP_LOCK = new Object()

    static Object forFile(File file) {
        String key
        try {
            key = file.getCanonicalPath()
        } catch (IOException ignored) {
            key = file.getAbsolutePath()
        }
        Object lock = LOCKS.get(key)
        if (lock != null) {
            return lock
        }
        synchronized (MAP_LOCK) {
            lock = LOCKS.get(key)
            if (lock == null) {
                lock = new Object()
                LOCKS.put(key, lock)
            }
        }
        return lock
    }

}
