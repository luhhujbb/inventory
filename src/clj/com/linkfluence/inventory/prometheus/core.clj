(ns com.linkfluence.inventory.prometheus.core
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [response]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [clojure.tools.logging :as log]
            [clometheus.core :as c]
            [clojure.string :refer [lower-case]]
            [clometheus.txt-format :as txt]
            ;;import api handler
            [com.linkfluence.inventory.core :as inventory]
            [com.linkfluence.utils :as u]))

; @author Jean-Baptiste Besselat
; @Copyright Adot SAS 2020

;;mai api conf
(def conf (atom {}))

(def count-resources-gauge (atom nil))

(defn init-count-resources-gauge!
  [conf]
  (reset! count-resources-gauge
    (c/gauge
      "inventory_resources_count"
      :description
      "resources count for a specific tag"
      :labels (map lower-case (:tags conf)))))

(defn flat-agg
  [agg-ress tags]
  (let [ktags (map keyword tags)
        root-tag (first (keys agg-ress))
        rtags-values (root-tag agg-ress)]
  (if (= 1 (count tags))
    (map (fn [[k v]]
      {:labels {(lower-case (name root-tag)) (lower-case (name k))}
       :ct (count v)})
       rtags-values)
  (mapcat
    (fn [[k v]]
      (let [sub-buckets (flat-agg v (rest tags))]
        (map
          (fn [{:keys [labels ct]}]
            {:ct ct
             :labels (merge
                        labels
                        {(lower-case (name root-tag)) (lower-case (name k))})})
            sub-buckets)))
      rtags-values))))

(defn generate-response
  []
    (let [agg-ress (inventory/get-aggregated-resources (:tags @conf) false [])
          prom-agg (flat-agg agg-ress (:tags @conf))]
          (doseq [ress-bucket prom-agg]
            (c/set!
              @count-resources-gauge
              (:ct ress-bucket)
              :labels (:labels ress-bucket)))
  (txt/metrics-response)))

(defroutes app-routes
  (GET "/metrics" [] (generate-response)))

(def handler (-> app-routes
                 (wrap-content-type)
                 (wrap-gzip)))

(defn configure!
  [{:keys [host port] :or {host "127.0.0.1" port 8081} :as prom-conf}]
  (reset! conf prom-conf)
  (init-count-resources-gauge! prom-conf)
  (defonce server (run-jetty #'handler {:port port :host host :join? false})))
