(ns back-channeling.components.board
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [back-channeling.api :as api]
            [back-channeling.notification :as notification]
            [back-channeling.audio :as audio]
            [back-channeling.components.avatar :refer [avatar]]
            [back-channeling.components.comment :refer [comment-view]]
            [goog.i18n.DateTimeSymbols_ja])
  (:use [back-channeling.comment-helper :only [format-plain]]
        [back-channeling.component-helper :only [make-click-outside-fn]])
  (:import [goog.i18n DateTimeFormat]))

(enable-console-print!)

(def date-format-m  (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME
                                     (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))

(defn save-comment [comment on-success]
  (if (= (:comment/format comment) :comment.format/voice)
    (let [blob (:comment/content comment)]
      (api/request (str "/api/thread/" (:thread/id comment) "/voices")
                   :POST
                   blob
                   {:format (case (.-type blob)
                              "audio/ogg" :ogg
                              "audio/wav" :wav) 
                    :handler (fn [response]
                               (api/request (str "/api/thread/" (:thread/id comment) "/comments")
                                            :POST
                                            (merge comment response) 
                                            {:handler #(on-success %)}))}))
    (api/request (str "/api/thread/" (:thread/id comment) "/comments")
               :POST
               comment
               {:handler (fn [response]
                           (on-success response))})))

(defn save-thread [thread]
  (api/request (str "/api/board/" (:board/name thread) "/threads")
               :POST
               thread
               {:handler (fn [response]
                           (set! (.-href js/location) (str "#/board/" (:board/name thread) "/" (:db/id response))))}))

(defn watch-thread [thread user owner]
  (api/request (str "/api/thread/" (:db/id thread))
               :PUT
               {:add-watcher user}
               {:handler (fn [response]
                           (om/set-state! owner :watching? true))})
  (notification/initialize))

(defn unwatch-thread [thread user owner]
  (api/request (str "/api/thread/" (:db/id thread))
               :PUT
               {:remove-watcher user}
               {:handler (fn [response]
                           (om/set-state! owner :watching? false))}))

(defcomponent comment-new-view [thread owner {:keys [board-name]}]
  (init-state [_]
    {:comment {:comment/content ""
               :comment/format "comment.format/plain"
               :thread/id (when thread (:db/id @thread)) }
     :focus? false
     :saving? false
     :recording-status :none
     :click-outside-fn nil})

  (will-mount [_]
    (when-let [on-click-outside (om/get-state owner :click-outside-fn)]
      (.removeEventListener js/document "mousedown" on-click-outside)))
  (did-mount [_]
    (when-not (om/get-state owner :click-outside-fn)
      (om/set-state! owner :click-outside-fn
                   (make-click-outside-fn
                    (om/get-node owner)
                    #(om/set-state! owner :focus? false))))
    (.addEventListener js/document "mousedown"
                       (om/get-state owner :click-outside-fn)))

  (did-update [_ _ _]
    (when-let [textarea (.. (om/get-node owner) (querySelector "textarea"))]
      (if (om/get-state owner :focus?)
        (.focus textarea))))
  
  (render-state [_ {:keys [comment focus? saving? error-map recording-status audio-url]}]
    (html
     [:form.ui.reply.form {:on-submit (fn [e] (.preventDefault e))}
      [:div.ui.equal.width.grid
       (if focus?
         [:div.row
          [:div.column
           [:div.field (when (:comment/content error-map)
                         {:class "error"})
            (if  (= (:comment/format comment) "comment.format/voice")
              (case recording-status
                :recording
                [:button.ui.large.red.circular.button
                 {:on-click (fn [e]
                              (letfn [(update-content [blob]
                                        (om/update-state!
                                         owner
                                         #(-> %
                                              (assoc :recording-status :none)
                                              (assoc-in [:comment :comment/content] blob))))]
                                (audio/stop-recording
                                 (fn [blob]
                                   (om/set-state! owner :recording-status :encoding)
                                   (if (= (.-type blob) "audio/wav")
                                     (audio/wav->ogg blob update-content)
                                     (update-content blob))))))}
                 [:i.large.stop.icon] "Stop"]

                :encoding
                [:button.ui.large.red.circular.disable.button
                 [:i.large.mute.icon] "Stopping..."]
                
                :none
                [:button.ui.large.basic.circular.red.button
                 {:on-click (fn [e]
                              (audio/start-recording)
                              (om/set-state! owner :recording-status :recording))}
                 [:i.large.unmute.icon] "Record"]))
            [:textarea
             (merge {:name "comment"
                     :value (:comment/content comment)
                     :on-change (fn [e]
                                  (when-not (= (om/get-state owner [:comment :comment/format]) "comment.format/voice")
                                    (om/set-state! owner [:comment :comment/content] (.. e -target -value))))
                     :on-key-up (fn [e]
                                  (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                                    (let [btn (.. (om/get-node owner) (querySelector "button.submit.button"))]
                                      (.click btn))))}
                    (when (= (:comment/format comment) "comment.format/voice")
                      {:style {:display "none"}}))]]
           [:div.actions
            [:div.two.fields
             [:div.field
              [:select {:name "format"
                        :value (:comment/format comment)
                        :on-change (fn [e]
                                     (om/set-state! owner [:comment :comment/format] (.. e -target -value)))}
               [:option {:value "comment.format/plain"} "Plain"]
               [:option {:value "comment.format/markdown"} "Markdown"]
               (when (audio/audio-available?)
                [:option {:value "comment.format/voice"} "Voice"])]]
             [:div.field
              [:button.ui.blue.labeled.submit.icon.button
               (merge {:on-click (fn [e]
                            (let [comment (om/get-state owner :comment)
                                  [result map] (b/validate comment
                                                           :comment/content v/required)]
                              (if result
                                (om/set-state! owner :error-map (:bouncer.core/errors map))
                                (do
                                  (om/set-state! owner :saving? true)
                                  (save-comment (update-in comment [:comment/format] keyword)
                                              (fn [_]
                                                (om/update-state!
                                                 owner
                                                 #(-> %
                                                      (assoc-in [:comment :comment/content] "")
                                                      (assoc :saving? false)))))))))}
                      (when saving?
                        {:class "loading"}))
               
               [:i.icon.edit] "New comment"]]]]]
          [:div.column
           [:div.preview
            [:div.ui.top.right.attached.label "Preview"]
            (case (:comment/format comment)
              "comment.format/plain"
              [:div.attached (format-plain (:comment/content comment) :board-name board-name :thread-id (:db/id thread))]

              "comment.format/markdown"
              [:div.attached {:key "preview-markdown"
                              :dangerouslySetInnerHTML {:__html (js/marked (:comment/content comment))}}]

              "comment.format/voice"
              [:div.attached
               (let [content (:comment/content comment)]
                 (when (instance? js/Blob content)
                   [:audio {:controls true
                            :src (.createObjectURL js/URL content)}]))])]]]
         [:div.row
          [:div.column
           [:div.ui.left.icon.input.field
            [:i.edit.icon]
            [:input {:type "text" :value (:comment/content comment) :on-focus (fn [_] (om/set-state! owner :focus? true))}]]]])]])))

(defn scroll-to-comment [owner thread]
  (let [comment-no (or (om/get-state owner :target-comment) (count (:thread/comments thread))) 
        comment-dom (.. (om/get-node owner)
                        (querySelector (str "[data-comment-no='" comment-no "']")))
        offset (.. (om/get-node owner)
                   (querySelector "[data-comment-no='1']")
                   getBoundingClientRect
                   -top)
        scroll-pane (.. (om/get-node owner)
                           (querySelector "div.scroll-pane"))]
    (when comment-dom
      (.scrollTo js/window 0
                 (- (+ (.. js/document -body -scrollTop) (some->> (.getBoundingClientRect comment-dom) (.-top))) 70)))))

(defcomponent thread-view [thread owner {:keys [board-name] :as opts}]
  (did-mount [_]
    (when thread
      (scroll-to-comment owner thread))
    )
  (did-update [_ _ _]
    (when thread
      (scroll-to-comment owner thread)))
  (render [_]
    (html
     [:div.ui.full.height.thread.comments
      [:h3.ui.dividing.header (:thread/title thread)]
      [:a.curation.link {:href (str "#/articles/new?thread-id=" (:db/id thread))}
       [:i.external.share.big.icon]]
      [:div.scroll-pane
       (for [comment (:thread/comments thread)]
         (om/build comment-view comment
                   {:opts {:thread thread :board-name board-name}}))]
      (if (>= (count (:thread/comments thread)) 1000)
        [:div.ui.error.message
         [:div.header "Over 1000 comments. You can't add any comment to this thread."]]
        (om/build comment-new-view thread {:opts opts}))])))

(defn toggle-sort-key [owner sort-key]
  (let [[col direction] (om/get-state owner :sort-key)]
    (om/set-state! owner :sort-key
                   [sort-key
                    (if (= col sort-key)
                      (case direction :asc :desc :desc :asc)
                      :asc)])))

(defcomponent thread-watch-icon [thread owner]
  (init-state [_]
    {:hover? false})
  (render-state [_ {:keys [watching? hover? user]}]
    (html
     [:td
      {:on-click (fn [_]
                   (if watching?
                     (unwatch-thread thread user owner)
                     (watch-thread thread user owner)))
       :on-mouse-over (fn [_] (om/set-state! owner :hover? true))
       :on-mouse-out  (fn [_] (om/set-state! owner :hover? false))}
      [:i.icon {:class (case [watching? hover?]
                         [true true]   "hide red"
                         [true false]  "unhide green"
                         [false true]  "unhide green"
                         [false false] "hide grey")}]])))

(defcomponent thread-list-view [threads owner {:keys [board-name]}]
  (init-state [_]
    {:sort-key [:thread/last-updated :desc]
     :user {:user/name  (.. js/document (querySelector "meta[property='bc:user:name']") (getAttribute "content"))
            :user/email (.. js/document (querySelector "meta[property='bc:user:email']") (getAttribute "content"))}})
  (render-state [_ {:keys [board-channel sort-key user]}]
    (html
     [:div.table.container
      [:div.tbody.container
       [:table.ui.compact.table
        [:thead
         [:tr
          [:th ""]
          [:th {:on-click (fn [_] (toggle-sort-key owner :thread/title))}
           "Title" [:div "Title " (when (= (first sort-key) :thread/title) (case (second sort-key)
                                                                             :asc  [:i.caret.up.icon]
                                                                             :desc [:i.caret.down.icon]))]]
          [:th {:on-click (fn [_] (toggle-sort-key owner :thread/resnum))}
           "Res"
           [:div "Res" (when (= (first sort-key) :thread/resnum) (case (second sort-key)
                                                          :asc  [:i.caret.up.icon]
                                                          :desc [:i.caret.down.icon]))]]
          [:th {:on-click (fn [_] (toggle-sort-key owner :thread/last-updated))}
           "Last updated"
           [:div "Last updated"
            (when (= (first sort-key) :thread/last-updated) (case (second sort-key)
                                                              :asc  [:i.caret.up.icon]
                                                              :desc [:i.caret.down.icon]))]]
          [:th {:on-click (fn [_] (toggle-sort-key owner :thread/since))}
           "Since"
           [:div "Since"
            (when (= (first sort-key) :thread/since) (case (second sort-key)
                                                              :asc  [:i.caret.up.icon]
                                                              :desc [:i.caret.down.icon]))]]]]
        [:tbody
         (for [thread (->> (vals threads)
                           (map #(if (:thread/watchers %) % (assoc % :thread/watchers #{})))
                           (sort-by (first sort-key) (case (second sort-key)
                                                       :asc < :desc >)))]
           [:tr
            (om/build thread-watch-icon thread {:init-state {:watching? (boolean ((:thread/watchers thread) (:user/name user)))
                                                             :user user}})
            [:td
             [:a {:href (str "#/board/" board-name "/" (:db/id thread))}
              (:thread/title thread)]]
            [:td (:thread/resnum thread)]
            [:td (.format date-format-m (:thread/last-updated thread))]
            [:td (.format date-format-m (:thread/since thread))]])]]]])))

(defcomponent thread-new-view [board owner]
  (init-state [_] {:thread {:board/name (:board/name @board)
                            :thread/title ""
                            :comment/content ""
                            :comment/format "comment.format/plain"}})
  (render-state [_ {:keys [thread error-map]}]
    (html
     [:form.ui.reply.form {:on-submit (fn [e] (.preventDefault e))}
      [:div.ui.equal.width.grid
       [:div.row
        [:div.column
         [:div.field (when (:thread/title error-map) {:class "error"})
          [:label "Title"]
          [:input {:type "text" :name "title" :value (:thread/title thread)
                   :on-change (fn [e] (om/set-state! owner [:thread :thread/title] (.. e -target -value)))}]]
         [:div.field (when (:comment/content error-map) {:class "error"})
          [:textarea {:name "comment"
                      :value (:comment/content thread)
                      :on-change (fn [e]
                                   (om/set-state! owner [:thread :comment/content] (.. e -target -value)))
                      :on-key-up (fn [e]
                                   (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                                     (let [btn (.. (om/get-node owner) (querySelector "button.submit.button"))]
                                       (.click btn))))}]]
         [:div.actions
          [:div.two.fields
           [:div.field
            [:select {:name "format"
                      :on-change (fn [e]
                                   (om/set-state! owner [:thread :comment/format] (.. e -target -value)))}
             [:option {:value "comment.format/plain"} "Plain"]
             [:option {:value "comment.format/markdown"} "Markdown"]]]
           [:div.field
            [:button.ui.blue.labeled.submit.icon.button
             {:on-click (fn [_]
                          (let [thread (om/get-state owner :thread)
                                [result map] (b/validate thread
                                                         :thread/title v/required
                                                         :comment/content v/required)]
                            (if result
                              (om/set-state! owner :error-map (:bouncer.core/errors map))
                              (do (save-thread thread)
                                  (om/update-state! owner [:thread]
                                                    #(assoc %
                                                            :comment/content ""
                                                            :thread/title ""))))))}
             [:i.icon.edit] "Create thread"]]]]]
        [:div.column
         [:div.preview
          [:div.ui.top.right.attached.label "Preview"]
          [:div
           (case (:comment/format thread)
             "comment.format/plain" (format-plain (:comment/content thread))
             "comment.format/markdown" {:dangerouslySetInnerHTML {:__html (js/marked (:comment/content thread))}})]]]]]])))


(defn sticky-thread-content-fn [owner]
  (let [thread-content (.. (om/get-node owner) (querySelector "div.thread.content"))]
    (fn [e]
      (if (< (.. thread-content getBoundingClientRect -top) 70)
        (om/set-state! owner :sticky-thread-content? true)
        (om/set-state! owner :sticky-thread-content? false)))))

(defcomponent board-view [board owner]
  (init-state [_]
    {:tabs [{:id 0 :name "New"}]
     :sticky-thread-content? false})

  (did-mount [_]
    (.addEventListener js/window "scroll"
                       (sticky-thread-content-fn owner)))
  
  (render-state [_ {:keys [tabs target-thread target-comment channel sticky-thread-content?]}]
    (if (->> tabs (filter #(= target-thread (:id %))) empty?)
        (om/update-state! owner :tabs #(conj % {:id target-thread :name (get-in board [:board/threads target-thread :thread/title])})))
    (html
     [:div.main.content.full.height
      (om/build thread-list-view (:board/threads board)
                {:init-state {:board-channel channel}
                 :opts {:board-name (:board/name board)}})
      [:div.ui.top.attached.thread.content.segment
       [:div.ui.top.attached.tabular.sticky.menu
        (when sticky-thread-content?
         {:class "fixed"
          :style {:margin-top "66px"
                  :width "874px"
                  :background-color "#ffffff"
                  :z-index "1"}})
        (for [tab tabs]
          [:a.item (merge {:on-click (fn [_]
                               (set! (.-href js/location)
                                     (if (= (:id tab) 0)
                                       "#/"
                                       (str "#/board/" (:board/name board) "/" (:id tab)))))}
                          (when (= target-thread (:id tab)) {:class "active"})) 
           [:span.tab-name (:name tab)] 
           (when (not= (:id tab) 0)
             [:span
              [:i.close.icon {:on-click (fn [e]
                                          (om/update-state! owner #(assoc %
                                                                          :tabs (vec (remove (fn [t] (= (:id t) (:id tab))) (:tabs %)))
                                                                          :target-thread (if (= (:target-thread %) (:id tab)) 0 (:target-thread %))) )
                                          (when (= target-thread (:id tab))
                                            (set! (.. js/location -href) "#/"))
                                          (.stopPropagation e))}]])])]
       (for [tab tabs]
         [:div.ui.bottom.attached.tab.full.height.segment
          (when (= target-thread (:id tab)) {:class "active"})
          (if (= target-thread 0)
            (om/build thread-new-view board)
            (om/build thread-view (get-in board [:board/threads target-thread])
                      {:state {:target-comment target-comment}
                       :opts {:board-name (:board/name board)}}))])]])))


