(ns swarmpit.component.plot
  (:require [cljsjs.plotly]
            [swarmpit.time :as time]
            ))

(defn default [plot-id data layout]
  (js/Plotly.newPlot
    (.getElementById js/document plot-id)
    (clj->js data)
    (clj->js layout)
    (clj->js {:responsive true})))

(defn single [plot-id stats-ts y-key title y-title]
  (let [time (:time stats-ts)
        now (last time)
        now-4-hours (time/in-past-string 60)]
    (default
      plot-id
      [{:x           (:time stats-ts)
        :y           (y-key stats-ts)
        :connectgaps false
        :fill        "tozeroy"
        :line        {:color "#43a047"}
        :type        "scatter"
        :mode        "lines"}]
      {:title  title
       :height 300
       :margin {:l   80
                :r   80
                :t   70
                :b   50
                :pad 0}
       :xaxis  {:range [now-4-hours now]}
       :yaxis  {:title      y-title
                :tickformat ".2f"
                :rangemode  "tozero"}})))

(defn multi [plot-id multi-stats-ts y-key title y-title]
  (let [time (:time (first multi-stats-ts))
        now (last time)
        now-4-hours (time/in-past-string 60)]
    (default
      plot-id
      (into []
            (map-indexed
              (fn [i item]
                (merge
                  (hash-map
                    :x (:time item)
                    :y (y-key item)
                    :name (subs (:name item) 0 15)
                    :connectgaps false
                    :fill "tozeroy"
                    :type "scatter"
                    :mode "lines")
                  (when (zero? i)
                    {:line {:color "#43a047"}}))) multi-stats-ts))
      {:title      title
       :showlegend true
       :height     300
       :margin     {:l   80
                    :r   80
                    :t   70
                    :b   50
                    :pad 0}
       :xaxis      {:range [now-4-hours now]}
       :yaxis      {:title      y-title
                    :tickformat ".2f"
                    :rangemode  "tozero"}})))
