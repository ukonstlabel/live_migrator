/**
 * @file agent.c
 * @brief Native JVMTI agent for heap walking and object tagging.
 *
 * This agent provides native heap walking capabilities for the Live Migrator framework.
 * It uses JVMTI (JVM Tool Interface) to iterate through the heap, tag objects, and
 * resolve tagged objects back to Java references.
 *
 * Key features:
 *   - Epoch-based object tagging for stable identification across GC cycles
 *   - Full heap walk to find all live objects
 *   - Per-class snapshot and filtered heap walk for multiple target classes
 *
 * Performance note: every matched object in a single walk is tagged with the SAME
 * per-walk tag value, and objects are resolved with one GetObjectsWithTags(count=1)
 * call. This matters because GetObjectsWithTags scans the whole tag map and does a
 * linear search over the supplied tag array per entry — passing N distinct tags makes
 * it O(N^2). A single shared tag keeps the resolve O(heap). The per-walk epoch makes
 * the shared tag unique to this walk, so tags left over from earlier walks never match.
 *
 * The agent can be loaded at JVM startup (-agentpath) or attached dynamically
 * to a running JVM via the Attach API.
 *
 * @see migrator.heap.NativeHeapWalker (Java counterpart)
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <limits.h>
#include <jni.h>
#include <jvmti.h>

static JavaVM* g_vm = NULL;
static jvmtiEnv* g_jvmti = NULL;

/**
 * Epoch counter. Bumped at the start of every walk so each walk uses a tag value
 * distinct from all prior walks. The tag written to every matched object is
 * WALK_TAG(g_epoch); resolving queries that single value.
 */
static volatile jlong g_epoch = 1;

/** The single tag value used for all objects matched during the current walk. */
#define WALK_TAG(epoch) ((jlong)((((uint64_t)(uint32_t)(epoch)) << 32) | 1ULL))

/**
 * heap_filter for IterateThroughHeap: 0 = no JVMTI_HEAP_FILTER_* bits set, i.e. report
 * every object. (Not JVMTI_HEAP_OBJECT_EITHER — that enum is for the deprecated
 * IterateOverHeap API and is not a valid heap_filter bitmask.)
 */
#define HEAP_FILTER_NONE 0

/**
 * Prints JVMTI error information to stderr.
 */
static void check_print(jvmtiEnv* jvmti, jvmtiError err, const char* msg) {
    if (err != JVMTI_ERROR_NONE) {
        char* name = NULL;
        if (jvmti) (*jvmti)->GetErrorName(jvmti, err, &name);
        fprintf(stderr, "[agent] JVMTI error %d (%s): %s\n",
                (int)err,
                name ? name : "UNKNOWN",
                msg);
        if (jvmti && name) (*jvmti)->Deallocate(jvmti, (unsigned char*) name);
    }
}

/**
 * JVMTI callback to tag heap objects for the current walk.
 *
 * The per-walk tag is passed via user_data (a pointer to the jlong walk_tag) so the
 * callback never reads the shared g_epoch — each walk tags with its own value, even if
 * walks overlap. An object already carrying this walk's tag is left as-is (cheap
 * idempotence when it matches several target classes); any other value is overwritten.
 */
static jint JNICALL heap_tagging_cb(
        jlong class_tag,
        jlong size,
        jlong* tag_ptr,
        jint length,
        void* user_data) {

    (void) class_tag;
    (void) size;
    (void) length;

    if (!tag_ptr || !user_data) return JVMTI_ITERATION_CONTINUE;

    jlong walk_tag = *(const jlong*) user_data;
    if (*tag_ptr == walk_tag) {
        return JVMTI_ITERATION_CONTINUE;
    }

    *tag_ptr = walk_tag;
    return JVMTI_ITERATION_CONTINUE;
}

/**
 * Resolves every object carrying the given per-walk tag into a Java Object[].
 *
 * One GetObjectsWithTags(count=1) call — O(heap), not O(heap * tags). Returns NULL
 * on error or when nothing matched. Deallocates all JVMTI-owned buffers.
 */
static jobjectArray resolve_walk_tag(JNIEnv* env, jlong walk_tag) {
    jint found = 0;
    jobject* objects = NULL;
    jlong* tagsOut = NULL;

    jvmtiError err = (*g_jvmti)->GetObjectsWithTags(
            g_jvmti, 1, &walk_tag, &found, &objects, &tagsOut);
    if (err != JVMTI_ERROR_NONE) {
        check_print(g_jvmti, err, "GetObjectsWithTags failed");
        if (objects) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
        if (tagsOut) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
        return NULL;
    }

    jobjectArray result = NULL;
    if (found > 0 && objects != NULL) {
        /* GetObjectsWithTags hands back `found` live local references at once; ask the
         * VM to guarantee room for them plus the array and class refs we create here. */
        if ((*env)->EnsureLocalCapacity(env, found + 16) != 0) {
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        }
        jclass objClass = (*env)->FindClass(env, "java/lang/Object");
        if (objClass != NULL) {
            result = (*env)->NewObjectArray(env, found, objClass, NULL);
            (*env)->DeleteLocalRef(env, objClass);
        }
        for (jint i = 0; i < found; i++) {
            jobject o = objects[i];
            if (result != NULL) {
                (*env)->SetObjectArrayElement(env, result, i, o);
            }
            if (o) (*env)->DeleteLocalRef(env, o);
        }
    }

    if (objects) {
        jvmtiError derr = (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) objects);
        check_print(g_jvmti, derr, "Deallocate(objects) failed");
    }
    if (tagsOut) {
        jvmtiError derr = (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*) tagsOut);
        check_print(g_jvmti, derr, "Deallocate(tagsOut) failed");
    }
    return result;
}

/**
 * Snapshot of all instances of a single class, returned as an Object[].
 *
 * The target class is passed as a jclass resolved by the Java caller, so instances are
 * found regardless of which classloader loaded the class.
 *
 * @param targetClass the class whose instances to snapshot
 * @return Object array of all instances, or NULL on error/empty
 */
JNIEXPORT jobjectArray JNICALL
Java_migrator_heap_NativeHeapWalker_nativeSnapshotObjects(
        JNIEnv* env,
        jclass cls,
        jclass targetClass) {

    (void) cls;

    if (!g_jvmti || !env || !targetClass) return NULL;

    /* Fresh epoch -> fresh per-walk tag (avoids an O(heap) clear of old tags). */
    jlong walk_tag = WALK_TAG(__sync_add_and_fetch(&g_epoch, 1));

    jvmtiHeapCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.heap_iteration_callback = &heap_tagging_cb;

    jvmtiError err = (*g_jvmti)->IterateThroughHeap(
            g_jvmti, HEAP_FILTER_NONE, targetClass, &callbacks, &walk_tag);
    if (err != JVMTI_ERROR_NONE) {
        check_print(g_jvmti, err, "IterateThroughHeap(snapshotObjects) failed");
        return NULL;
    }

    return resolve_walk_tag(env, walk_tag);
}

/**
 * Walks the entire heap and returns all objects.
 *
 * @return Array of all objects on the heap, or NULL on error
 */
JNIEXPORT jobjectArray JNICALL
Java_migrator_heap_NativeHeapWalker_nativeWalkHeap(JNIEnv *env, jobject thisObj) {
    (void) thisObj;

    if (!g_jvmti || !env) return NULL;

    jlong walk_tag = WALK_TAG(__sync_add_and_fetch(&g_epoch, 1));

    jvmtiHeapCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.heap_iteration_callback = &heap_tagging_cb;

    jvmtiError err = (*g_jvmti)->IterateThroughHeap(
            g_jvmti, HEAP_FILTER_NONE, NULL, &callbacks, &walk_tag);
    if (err != JVMTI_ERROR_NONE) {
        check_print(g_jvmti, err, "IterateThroughHeap(nativeWalkHeap) failed");
        return NULL;
    }

    return resolve_walk_tag(env, walk_tag);
}

/**
 * Walks the heap filtered by specific classes.
 *
 * All target classes are tagged within a single walk using the shared per-walk tag,
 * then resolved in one GetObjectsWithTags(count=1) call. Classes are passed as jclass
 * objects resolved by the Java caller, so they are found regardless of classloader.
 *
 * @param classesArray Array of target classes
 * @return Array of objects matching the specified classes, or NULL on error
 */
JNIEXPORT jobjectArray JNICALL
Java_migrator_heap_NativeHeapWalker_nativeWalkHeapFiltered(
    JNIEnv *env, jobject thisObj, jobjectArray classesArray) {

    (void) thisObj;

    if (!g_jvmti || !env || classesArray == NULL) return NULL;

    jsize nClasses = (*env)->GetArrayLength(env, classesArray);
    if (nClasses == 0) return NULL;

    jlong walk_tag = WALK_TAG(__sync_add_and_fetch(&g_epoch, 1));

    jvmtiHeapCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.heap_iteration_callback = &heap_tagging_cb;

    for (jsize ci = 0; ci < nClasses; ci++) {
        jclass targetClass = (jclass)(*env)->GetObjectArrayElement(env, classesArray, ci);
        if (targetClass == NULL) continue;

        /* Tag instances of this class with the shared per-walk tag. The tag check in
         * heap_tagging_cb makes a re-tag a no-op when an object matches several target
         * classes (e.g. via inheritance). */
        jvmtiError err = (*g_jvmti)->IterateThroughHeap(g_jvmti, HEAP_FILTER_NONE,
                                                         targetClass, &callbacks, &walk_tag);
        if (err != JVMTI_ERROR_NONE) {
            check_print(g_jvmti, err, "IterateThroughHeap failed for class");
        }

        (*env)->DeleteLocalRef(env, targetClass);
    }

    return resolve_walk_tag(env, walk_tag);
}

/**
 * Advances the epoch counter.
 * Called after migration completes to invalidate old tags.
 */
JNIEXPORT void JNICALL
Java_migrator_heap_NativeHeapWalker_nativeAdvanceEpoch(
        JNIEnv* env,
        jclass cls) {
    (void) env;
    (void) cls;
    __sync_fetch_and_add(&g_epoch, 1);
}

/**
 * Initializes the agent by obtaining JVMTI environment and requesting capabilities.
 */
static jint agent_start(JavaVM* vm) {
    if (!vm) return JNI_ERR;
    g_vm = vm;

    jint getenv_res = (*vm)->GetEnv(vm, (void**)&g_jvmti, JVMTI_VERSION_1_2);
    if (getenv_res != JNI_OK || g_jvmti == NULL) {
        fprintf(stderr, "[agent] Failed to get JVMTI env\n");
        return JNI_ERR;
    }

    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_tag_objects = 1;
    jvmtiError err = (*g_jvmti)->AddCapabilities(g_jvmti, &caps);
    if (err != JVMTI_ERROR_NONE) {
        check_print(g_jvmti, err, "AddCapabilities failed");
        return JNI_ERR;
    }

    return JNI_OK;
}

/**
 * Agent entry point for JVM startup (-agentpath).
 */
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void* reserved) {
    (void) options;
    (void) reserved;
    return agent_start(vm);
}

/**
 * Agent entry point for dynamic attach (Attach API).
 */
JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void* reserved) {
    (void) options;
    (void) reserved;
    return agent_start(vm);
}
