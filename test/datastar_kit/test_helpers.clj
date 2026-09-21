(ns datastar-kit.test-helpers
  "Shared test-only support for simulating a consumer's classpath shape
   (development, thin-JAR image) around datastar-kit.assets/copy-audit and
   datastar-kit.testing. Not part of the kit's public surface."
  (:require
   [clojure.java.io :as io])
  (:import
   (java.net URL URLClassLoader)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn create-temp-dir
  "Create and return a fresh temp directory (java.io.File) for one test's
   classpath fixtures."
  ^java.io.File [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn spit-file
  "Write content to <root>/<relative-path>, creating parent directories as
   needed. relative-path uses forward slashes."
  [root relative-path content]
  (let [f (io/file root relative-path)]
    (io/make-parents f)
    (spit f content)
    f))

(defmacro with-classpath-dir
  "Run body with the thread context class loader temporarily set to a
   URLClassLoader whose sole classpath root is dir, chained to parent-loader.
   Restores the original context class loader afterward, even on error.

   Nest calls to combine multiple classpath roots (each nested call's parent
   becomes the next enclosing loader), which is how a test simulates more
   than one provider for the same resource path."
  [dir parent-loader & body]
  `(let [original# (.getContextClassLoader (Thread/currentThread))
         loader# (URLClassLoader. (into-array URL [(.toURL (.toURI ~dir))])
                                  ~parent-loader)]
     (try
       (.setContextClassLoader (Thread/currentThread) loader#)
       ~@body
       (finally
         (.setContextClassLoader (Thread/currentThread) original#)))))
