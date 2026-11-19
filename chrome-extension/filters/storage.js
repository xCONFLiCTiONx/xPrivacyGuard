(function (root) {
  "use strict";

  const DATABASE_NAME = "xPrivacyGuardFilters";
  const DATABASE_VERSION = 1;
  let databasePromise;

  function openDatabase() {
    if (!databasePromise) {
      databasePromise = new Promise((resolve, reject) => {
        const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
        request.onupgradeneeded = () => {
          const db = request.result;
          if (!db.objectStoreNames.contains("lists")) {
            db.createObjectStore("lists", { keyPath: "id" });
          }
        };
        request.onsuccess = () => {
          const db = request.result;

          // Clear cached promise if the database connection drops or closes unexpectedly
          db.onclose = () => {
            databasePromise = null;
          };
          db.onversionchange = () => {
            db.close();
            databasePromise = null;
          };

          resolve(db);
        };
        request.onerror = () => {
          databasePromise = null;
          reject(request.error || new Error("Could not open the filter database."));
        };
      });
    }
    return databasePromise;
  }

  async function transact(mode, operation) {
    let db;
    try {
      db = await openDatabase();
    } catch (err) {
      databasePromise = null;
      db = await openDatabase(); // Retry once on failure
    }

    return new Promise((resolve, reject) => {
      try {
        const transaction = db.transaction("lists", mode);
        const store = transaction.objectStore("lists");
        let result;

        try {
          result = operation(store);
        } catch (error) {
          reject(error);
          return;
        }

        transaction.oncomplete = () => resolve(result && result.result);
        transaction.onerror = () => {
          reject(transaction.error || new Error("Filter database transaction failed."));
        };
        transaction.onabort = () => {
          reject(transaction.error || new Error("Filter database transaction was aborted."));
        };
      } catch (error) {
        // If the connection closed mid-call, invalidate cache and throw clear message
        if (error.name === "InvalidStateError") {
          databasePromise = null;
        }
        reject(error);
      }
    });
  }

  root.FilterStorage = {
    put(record) {
      return transact("readwrite", (store) => store.put(record));
    },
    get(id) {
      return transact("readonly", (store) => store.get(id));
    },
    getAll() {
      return transact("readonly", (store) => store.getAll());
    },
    remove(id) {
      return transact("readwrite", (store) => store.delete(id));
    }
  };
})(globalThis);