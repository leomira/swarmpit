(ns swarmpit.handler
  (:require [clojure.walk :refer [keywordize-keys]]
            [net.cgrand.enlive-html :as html :refer [deftemplate]]
            [swarmpit.api :as api]
            [swarmpit.slt :as slt]
            [swarmpit.token :as token]
            [swarmpit.stats :as stats]
            [swarmpit.version :as version]))

(defn include-css [href revision]
  (first (html/html [:link {:href (str href "?r=" revision) :rel "stylesheet"}])))

(defn include-js [src revision]
  (first (html/html [:script {:src (str src "?r=" revision)}])))

(deftemplate index "index.html"
             [revision]
             [:head] (html/append (map #(include-css % revision) ["css/main.css"]))
             [:body] (html/append (map #(include-js % revision) ["js/main.js"])))

(defn resp-error
  [status response]
  {:status status
   :body   {:error response}})

(defn resp-unauthorized
  [response]
  {:status 401
   :body   {:error response}})

(defn resp-ok
  ([] {:status 200})
  ([response] {:status 200
               :body   response}))

(defn resp-created
  ([] {:status 201})
  ([response] {:status 201
               :body   response}))

(defn resp-accepted
  ([] {:status 202})
  ([response] {:status 202
               :body   response}))

;;; Handler

(defmulti dispatch identity)

;; Index handler

(defmethod dispatch :index [_]
  (fn [_]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (apply str (index (:revision (version/info))))}))

;; Version handler

(defmethod dispatch :version [_]
  (fn [_]
    (->> (version/info)
         (resp-ok))))

;; SLT handler

(defmethod dispatch :slt [_]
  (fn [_]
    (resp-ok {:slt (slt/create)})))

;; Login handler

(defmethod dispatch :login [_]
  (fn [{:keys [headers]}]
    (let [token (get headers "authorization")]
      (if (nil? token)
        (resp-error 400 "Missing token")
        (let [user (->> (token/decode-basic token)
                        (api/user-by-credentials))]
          (if (nil? user)
            (resp-unauthorized "Invalid credentials")
            (resp-ok {:token (token/generate-jwt user)})))))))

;; Password handler

(defmethod dispatch :password [_]
  (fn [{:keys [params identity]}]
    (let [username (get-in identity [:usr :username])
          payload (keywordize-keys params)]
      (if (api/user-by-credentials (merge payload {:username username}))
        (do (-> (api/user-by-username username)
                (api/change-password (:new-password payload)))
            (resp-ok))
        (resp-error 403 "Invalid old password provided")))))


;; User api token handler

(defmethod dispatch :api-token-generate [_]
  (fn [{:keys [identity]}]
    (->> identity :usr :username
         (api/user-by-username)
         (api/generate-api-token)
         (resp-ok))))

(defmethod dispatch :api-token-remove [_]
  (fn [{:keys [identity]}]
    (->> identity :usr :username
         (api/user-by-username)
         (api/remove-api-token))
    (resp-ok)))

;; User handler

(defmethod dispatch :initialize [_]
  (fn [{:keys [params]}]
    (if (api/admin-exists?)
      (resp-error 403 "Admin already exists")
      (let [user (merge (keywordize-keys params) {:type "user" :role "admin"})
            response (api/create-user user)]
        (if (some? response)
          (resp-created (select-keys response [:id]))
          (resp-error 400 "User already exist"))))))

(defmethod dispatch :me [_]
  (fn [{:keys [identity]}]
    (-> identity :usr :username
        (api/user-by-username)
        (select-keys [:username :email :role :api-token])
        (resp-ok))))

(defmethod dispatch :users [_]
  (fn [_]
    (->> (api/users)
         (resp-ok))))

(defmethod dispatch :user [_]
  (fn [{:keys [route-params]}]
    (let [user (api/user (:id route-params))]
      (if (some? user)
        (resp-ok user)
        (resp-error 404 "user doesn't exist")))))

(defmethod dispatch :user-delete [_]
  (fn [{:keys [route-params identity]}]
    (let [user (get-in identity [:usr :username])]
      (if (= (:_id (api/user-by-username user))
             (:id route-params))
        (resp-error 400 "Operation not allowed")
        (let [user (api/user (:id route-params))]
          (api/delete-user (:id route-params))
          (api/delete-user-registries (:username user))
          (resp-ok))))))

(defmethod dispatch :user-create [_]
  (fn [{:keys [params]}]
    (let [payload (assoc (keywordize-keys params) :type "user")
          response (api/create-user payload)]
      (if (some? response)
        (resp-created (select-keys response [:id]))
        (resp-error 400 "User already exist")))))

(defmethod dispatch :user-update [_]
  (fn [{:keys [route-params params]}]
    (let [payload (keywordize-keys params)]
      (api/update-user (:id route-params) payload)
      (resp-ok))))

;; Service handler

(defmethod dispatch :services [_]
  (fn [_]
    (->> (api/services)
         (resp-ok))))

(defmethod dispatch :service [_]
  (fn [{:keys [route-params]}]
    (->> (api/service (:id route-params))
         (resp-ok))))

(defmethod dispatch :service-networks [_]
  (fn [{:keys [route-params]}]
    (->> (api/service-networks (:id route-params))
         (resp-ok))))

(defmethod dispatch :service-tasks [_]
  (fn [{:keys [route-params]}]
    (->> (api/service-tasks (:id route-params))
         (resp-ok))))

(defmethod dispatch :service-logs [_]
  (fn [{:keys [route-params query-params]}]
    (->> (keywordize-keys query-params)
         :since
         (api/service-logs (:id route-params))
         (resp-ok))))

(defmethod dispatch :service-create [_]
  (fn [{:keys [params identity]}]
    (let [owner (get-in identity [:usr :username])
          payload (keywordize-keys params)]
      (->> (api/create-service owner payload)
           (resp-created)))))

(defmethod dispatch :service-update [_]
  (fn [{:keys [params identity]}]
    (let [owner (get-in identity [:usr :username])
          payload (keywordize-keys params)]
      (api/update-service owner payload)
      (resp-ok))))

(defmethod dispatch :service-redeploy [_]
  (fn [{:keys [route-params identity]}]
    (let [owner (get-in identity [:usr :username])]
      (api/redeploy-service owner (:id route-params))
      (resp-accepted))))

(defmethod dispatch :service-rollback [_]
  (fn [{:keys [route-params identity]}]
    (let [owner (get-in identity [:usr :username])]
      (api/rollback-service owner (:id route-params))
      (resp-accepted))))

(defmethod dispatch :service-delete [_]
  (fn [{:keys [route-params]}]
    (api/delete-service (:id route-params))
    (resp-ok)))

(defmethod dispatch :service-compose [_]
  (fn [{:keys [route-params]}]
    (let [response (api/service-compose (:id route-params))]
      (if (some? response)
        (resp-ok {:name (:name route-params)
                  :spec {:compose response}})
        (resp-error 400 "Failed to create compose file")))))

(defmethod dispatch :labels-service [_]
  (fn [_]
    (resp-ok (api/labels-service))))

;; Network handler

(defmethod dispatch :networks [_]
  (fn [_]
    (->> (api/networks)
         (resp-ok))))

(defmethod dispatch :network [_]
  (fn [{:keys [route-params]}]
    (->> (api/network (:id route-params))
         (resp-ok))))

(defmethod dispatch :network-services [_]
  (fn [{:keys [route-params]}]
    (->> (api/services-by-network (:id route-params))
         (resp-ok))))

(defmethod dispatch :network-create [_]
  (fn [{:keys [params]}]
    (let [payload (keywordize-keys params)]
      (->> (api/create-network payload)
           (resp-created)))))

(defmethod dispatch :network-delete [_]
  (fn [{:keys [route-params]}]
    (api/delete-network (:id route-params))
    (resp-ok)))

;; Volume handler

(defmethod dispatch :volumes [_]
  (fn [_]
    (->> (api/volumes)
         (resp-ok))))

(defmethod dispatch :volume [_]
  (fn [{:keys [route-params]}]
    (->> (api/volume (:name route-params))
         (resp-ok))))

(defmethod dispatch :volume-services [_]
  (fn [{:keys [route-params]}]
    (->> (api/services-by-volume (:name route-params))
         (resp-ok))))

(defmethod dispatch :volume-create [_]
  (fn [{:keys [params]}]
    (let [payload (keywordize-keys params)]
      (->> (api/create-volume payload)
           (resp-created)))))

(defmethod dispatch :volume-delete [_]
  (fn [{:keys [route-params]}]
    (api/delete-volume (:name route-params))
    (resp-ok)))

;; Secret handler

(defmethod dispatch :secrets [_]
  (fn [_]
    (->> (api/secrets)
         (resp-ok))))

(defmethod dispatch :secret [_]
  (fn [{:keys [route-params]}]
    (->> (api/secret (:id route-params))
         (resp-ok))))

(defmethod dispatch :secret-services [_]
  (fn [{:keys [route-params]}]
    (->> (api/services-by-secret (:id route-params))
         (resp-ok))))

(defmethod dispatch :secret-create [_]
  (fn [{:keys [params]}]
    (let [payload (keywordize-keys params)]
      (->> (api/create-secret payload)
           (resp-created)))))

(defmethod dispatch :secret-delete [_]
  (fn [{:keys [route-params]}]
    (api/delete-secret (:id route-params))
    (resp-ok)))

(defmethod dispatch :secret-update [_]
  (fn [{:keys [route-params params]}]
    (let [payload (keywordize-keys params)]
      (api/update-secret (:id route-params) payload)
      (resp-ok))))

;; Config handler

(defmethod dispatch :configs [_]
  (fn [_]
    (->> (api/configs)
         (resp-ok))))

(defmethod dispatch :config [_]
  (fn [{:keys [route-params]}]
    (->> (api/config (:id route-params))
         (resp-ok))))

(defmethod dispatch :config-services [_]
  (fn [{:keys [route-params]}]
    (->> (api/services-by-config (:id route-params))
         (resp-ok))))

(defmethod dispatch :config-create [_]
  (fn [{:keys [params]}]
    (let [payload (keywordize-keys params)]
      (->> (api/create-config payload)
           (resp-created)))))

(defmethod dispatch :config-delete [_]
  (fn [{:keys [route-params]}]
    (api/delete-config (:id route-params))
    (resp-ok)))

;; Node handler

(defmethod dispatch :nodes [_]
  (fn [_]
    (->> (api/nodes)
         (resp-ok))))

(defmethod dispatch :nodes-ts [_]
  (fn [_]
    (->> (stats/hosts-timeseries)
         (resp-ok))))

(defmethod dispatch :node [_]
  (fn [{:keys [route-params]}]
    (->> (api/node (:id route-params))
         (resp-ok))))

(defmethod dispatch :node-update [_]
  (fn [{:keys [route-params params]}]
    (let [payload (keywordize-keys params)]
      (api/update-node (:id route-params) payload)
      (resp-ok))))

(defmethod dispatch :node-tasks [_]
  (fn [{:keys [route-params]}]
    (->> (api/node-tasks (:id route-params))
         (resp-ok))))

;; Statistics

(defmethod dispatch :stats [_]
  (fn [_]
    (if (stats/ready?)
      (->> (stats/cluster)
           (resp-ok))
      (resp-error 400 "Statistics not ready"))))

;; Placement handler

(defmethod dispatch :placement [_]
  (fn [_]
    (->> (api/placement)
         (resp-ok))))

;; Plugin handler

(defmethod dispatch :plugin-network [_]
  (fn [_]
    (->> (api/plugins-by-type "Network")
         (filter #(not (contains? #{"null" "host"} %)))
         (resp-ok))))

(defmethod dispatch :plugin-log [_]
  (fn [_]
    (->> (api/plugins-by-type "Log")
         (resp-ok))))

(defmethod dispatch :plugin-volume [_]
  (fn [_]
    (->> (api/plugins-by-type "Volume")
         (resp-ok))))

;; Task handler

(defmethod dispatch :tasks [_]
  (fn [_]
    (->> (api/tasks)
         (resp-ok))))

(defmethod dispatch :task [_]
  (fn [{:keys [route-params]}]
    (->> (api/task (:id route-params))
         (resp-ok))))

(defmethod dispatch :task-ts [_]
  (fn [{:keys [route-params]}]
    (->> (stats/task-timeseries (:name route-params))
         (resp-ok))))

;; Registry handler

(defmethod dispatch :registries [_]
  (fn [{:keys [identity route-params]}]
    (let [owner (get-in identity [:usr :username])
          registry (keyword (:registryType route-params))]
      (if (api/supported-registry-type? registry)
        (->> (case registry
               :v2 (api/registries-v2 owner)
               :dockerhub (api/dockerhubs owner)
               :ecr (api/registries-ecr owner)
               :acr (api/registries-acr owner)
               :gitlab (api/registries-gitlab owner))
             (resp-ok))
        (resp-error 400 (str "Unknown registry type [" registry "]"))))))

(defmethod dispatch :registry [_]
  (fn [{:keys [route-params]}]
    (let [id (:id route-params)
          registry (keyword (:registryType route-params))]
      (if (api/supported-registry-type? registry)
        (->> (case registry
               :v2 (api/registry-v2 id)
               :dockerhub (api/dockerhub id)
               :ecr (api/registry-ecr id)
               :acr (api/registry-acr id)
               :gitlab (api/registry-gitlab id))
             (resp-ok))
        (resp-error 400 (str "Unknown registry type [" registry "]"))))))

(defmethod dispatch :registry-delete [_]
  (fn [{:keys [route-params]}]
    (let [id (:id route-params)
          registry (keyword (:registryType route-params))]
      (if (api/supported-registry-type? registry)
        (do (case registry
              :v2 (api/delete-v2-registry id)
              :dockerhub (api/delete-dockerhub id)
              :ecr (api/delete-ecr-registry id)
              :acr (api/delete-acr-registry id)
              :gitlab (api/delete-gitlab-registry id))
            (resp-ok))
        (resp-error 400 (str "Unknown registry type [" registry "]"))))))

(defmethod dispatch :registry-repositories [_]
  (fn [{:keys [route-params]}]
    (let [id (:id route-params)
          registry (keyword (:registryType route-params))]
      (if (api/supported-registry-type? registry)
        (->> (case registry
               :v2 (api/registry-v2-repositories id)
               :dockerhub (api/dockerhub-repositories id)
               :ecr (api/registry-ecr-repositories id)
               :acr (api/registry-acr-repositories id)
               :gitlab (api/registry-gitlab-repositories id))
             (resp-ok))
        (resp-error 400 (str "Unknown registry type " registry))))))

;;; Registry CREATE handler

(defmulti registry-create (fn [registry payload] registry))

(defmethod registry-create :v2
  [_ payload]
  (try
    (api/registry-v2-info payload)
    (let [response (api/create-v2-registry payload)]
      (if (some? response)
        (resp-created (select-keys response [:id]))
        (resp-error 400 "Registry account already linked")))
    (catch Exception e
      (resp-error 400 (get-in (ex-data e) [:body :error])))))

(defmethod registry-create :dockerhub
  [_ payload]
  (let [dockeruser-token (:token (api/dockerhub-login payload))
        dockeruser-info (api/dockerhub-info payload)
        dockeruser-namespace (api/dockerhub-namespace dockeruser-token)
        response (api/create-dockerhub payload dockeruser-info dockeruser-namespace)]
    (if (some? response)
      (resp-created (select-keys response [:id]))
      (resp-error 400 "Dockerhub account already linked"))))

(defmethod registry-create :ecr
  [_ payload]
  (try
    (let [url (:proxyEndpoint (api/registry-ecr-token payload))
          response (api/create-ecr-registry (assoc payload :url url))]
      (if (some? response)
        (resp-created (select-keys response [:id]))
        (resp-error 400 "AWS ECR account already linked")))
    (catch Exception e
      (resp-error 400 (get-in (ex-data e) [:body :error])))))

(defmethod registry-create :acr
  [_ payload]
  (try
    (let [url (api/acr-url payload)
          payload (assoc payload :url url)
          info (api/registry-acr-info payload)
          response (api/create-acr-registry payload)]
      (if (some? response)
        (resp-created (select-keys response [:id]))
        (resp-error 400 "Azure ACR account with given service principals already linked")))
    (catch Exception e
      (resp-error 400 (get-in (ex-data e) [:body :error])))))

(defmethod registry-create :gitlab
  [_ payload]
  (try
    (api/registry-gitlab-info payload)
    (let [response (api/create-gitlab-registry payload)]
      (if (some? response)
        (resp-created (select-keys response [:id]))
        (resp-error 400 "Gitlab registry account already linked")))
    (catch Exception e
      (resp-error 400 (get-in (ex-data e) [:body :error])))))

(defmethod dispatch :registry-create [_]
  (fn [{:keys [params identity route-params]}]
    (let [owner (get-in identity [:usr :username])
          registry (keyword (:registryType route-params))
          payload (assoc (keywordize-keys params) :owner owner
                                                  :type registry)]
      (if (api/supported-registry-type? registry)
        (registry-create registry payload)
        (resp-error 400 (str "Unknown registry type [" registry "]"))))))

;;; Registry UPDATE handler

(defmulti registry-update (fn [registry payload id] registry))

(defmethod registry-update :v2
  [_ payload id]
  (try
    (api/registry-v2-info payload)
    (api/update-v2-registry id payload)
    (resp-ok)
    (catch Exception e
      (resp-error 400 (get-in (ex-data e) [:body :error])))))

(defmethod registry-update :dockerhub
  [_ payload id]
  (when (:password payload)
    (api/dockerhub-login payload))
  (api/update-dockerhub id payload)
  (resp-ok))

(defmethod registry-update :ecr
  [_ payload id]
  (try
    (let [url (:proxyEndpoint (api/registry-ecr-token payload))
          delta-payload (assoc payload :url url)]
      (api/update-ecr-registry id delta-payload)
      (resp-ok))
    (catch Exception e
      (resp-error 400 (get-in (ex-data e) [:body :error])))))

(defmethod registry-update :acr
  [_ payload id]
  (try
    (api/registry-acr-info payload)
    (api/update-acr-registry id payload)
    (resp-ok)
    (catch Exception e
      (resp-error 400 (get-in (ex-data e) [:body :error])))))

(defmethod registry-update :gitlab
  [_ payload id]
  (try
    (api/registry-gitlab-info payload)
    (api/update-gitlab-registry id payload)
    (resp-ok)
    (catch Exception e
      (resp-error 400 (get-in (ex-data e) [:body :error])))))

(defmethod dispatch :registry-update [_]
  (fn [{:keys [route-params params]}]
    (let [payload (keywordize-keys params)
          id (:id route-params)
          registry (keyword (:registryType route-params))]
      (if (api/supported-registry-type? registry)
        (registry-update registry payload id)
        (resp-error 400 (str "Unknown registry type [" registry "]"))))))

;; Public dockerhub handler

(defmethod dispatch :public-repositories [_]
  (fn [{:keys [query-params]}]
    (let [query-params (keywordize-keys query-params)
          repository-query (:query query-params)
          repository-page (:page query-params)]
      (->> (api/public-repositories repository-query repository-page)
           (resp-ok)))))

;; Repository handler

(defmethod dispatch :repository-tags [_]
  (fn [{:keys [query-params identity]}]
    (let [owner (get-in identity [:usr :username])
          query-params (keywordize-keys query-params)
          repository-name (:repository query-params)]
      (if (nil? repository-name)
        (resp-error 400 "Parameter name missing")
        (->> (api/repository-tags owner repository-name)
             (resp-ok))))))

(defmethod dispatch :repository-ports [_]
  (fn [{:keys [query-params identity]}]
    (let [owner (get-in identity [:usr :username])
          query-params (keywordize-keys query-params)
          repository-name (:repository query-params)
          repository-tag (:repositoryTag query-params)]
      (if (or (nil? repository-name)
              (nil? repository-tag))
        (resp-error 400 "Parameter name or tag missing")
        (->> (api/repository-ports owner repository-name repository-tag)
             (resp-ok))))))

;; Stack handler

(defmethod dispatch :stacks [_]
  (fn [_]
    (->> (api/stacks)
         (resp-ok))))

(defmethod dispatch :stack-create [_]
  (fn [{:keys [identity params]}]
    (let [owner (get-in identity [:usr :username])
          payload (keywordize-keys params)]
      (if (some? (api/stack (:name payload)))
        (resp-error 400 "Stack already exist.")
        (do (api/create-stack owner payload)
            (resp-created))))))

(defmethod dispatch :stack-update [_]
  (fn [{:keys [identity route-params params]}]
    (let [owner (get-in identity [:usr :username])
          payload (keywordize-keys params)]
      (if (not= (:name route-params)
                (:name payload))
        (resp-error 400 "Stack invalid.")
        (do (api/update-stack owner payload)
            (resp-ok))))))

(defmethod dispatch :stack-redeploy [_]
  (fn [{:keys [route-params identity]}]
    (let [owner (get-in identity [:usr :username])]
      (api/redeploy-stack owner (:name route-params))
      (resp-ok))))

(defmethod dispatch :stack-rollback [_]
  (fn [{:keys [route-params identity]}]
    (let [owner (get-in identity [:usr :username])]
      (api/rollback-stack owner (:name route-params))
      (resp-ok))))

(defmethod dispatch :stack-delete [_]
  (fn [{:keys [route-params]}]
    (let [{:keys [result]} (api/delete-stack (:name route-params))]
      (if (nil? (api/stack (:name route-params)))
        (resp-ok)
        (resp-error 400 result)))))

(defmethod dispatch :stack-file [_]
  (fn [{:keys [route-params]}]
    (let [response (api/stackfile (:name route-params))]
      (if (some? response)
        (resp-ok response)
        (resp-error 400 "Stackfile not found")))))

(defmethod dispatch :stack-compose [_]
  (fn [{:keys [route-params]}]
    (let [response (api/stack-compose (:name route-params))]
      (if (some? response)
        (resp-ok {:name (:name route-params)
                  :spec {:compose response}})
        (resp-error 400 "Failed to create compose file")))))

(defmethod dispatch :stack-services [_]
  (fn [{:keys [route-params]}]
    (-> (api/stack-services (:name route-params))
        (resp-ok))))

(defmethod dispatch :stack-networks [_]
  (fn [{:keys [route-params]}]
    (-> (api/stack-services (:name route-params))
        (api/resources-by-services :networks api/networks)
        (resp-ok))))

(defmethod dispatch :stack-volumes [_]
  (fn [{:keys [route-params]}]
    (-> (api/stack-services (:name route-params))
        (api/volumes-by-services)
        (resp-ok))))

(defmethod dispatch :stack-configs [_]
  (fn [{:keys [route-params]}]
    (-> (api/stack-services (:name route-params))
        (api/resources-by-services :configs api/configs)
        (resp-ok))))

(defmethod dispatch :stack-secrets [_]
  (fn [{:keys [route-params]}]
    (-> (api/stack-services (:name route-params))
        (api/resources-by-services :secrets api/secrets)
        (resp-ok))))
