(ns app.insurance.index.views
  (:require
   [app.auth :as auth]
   [app.config :as config]
   [app.datastar :as d*]
   [app.insurance.actions :as actions]
   [app.insurance.index.queries :as queries]
   [app.insurance.ui :as insurance.ui]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]
   [app.urls :as urls]))

(def policy-status-data
  {:insurance.policy.status/active {:icon "circle-check-outline" :class "insurance-policy-status-icon--active"}
   :insurance.policy.status/sent   {:icon "envelope"             :class "insurance-policy-status-icon--sent"}
   :insurance.policy.status/draft  {:icon "circle-dot-outline"   :class "insurance-policy-status-icon--draft"}})

(defn- policy-status-icon [status]
  (let [{:keys [icon class]} (policy-status-data status)]
    (when icon
      [ico/Icon {::ico/library :snoico
                 ::ico/name    icon
                 :class        (ui2/cs "insurance-policy-status-icon" class)
                 :aria-hidden  true}])))

(defn- policy-remove-dialog [{:keys [tr] :as req} {:insurance.policy/keys [policy-id name]}]
  (let [loading-id (pr-str (str policy-id))]
    [:wa-dialog {:id    (ui2/remove-dialog-id "insurance-policy" policy-id)
                 :label (tr [:action/confirm-generic])}
     [:p (tr [:action/confirm-delete-policy] [name])]
     [button/Button {:slot        "footer"
                     :appearance  "outlined"
                     :data-dialog "close"}
      (tr [:action/cancel])]
     [button/Button {:slot               "footer"
                     :appearance         "filled"
                     :variant            "danger"
                     :data-dialog        "close"
                     :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                     :data-attr:loading  (str "$loading === " loading-id)
                     :data-id            policy-id
                     :data-action        (d*/act req ::actions/delete-policy)}
      (tr [:action/confirm-delete])]]))

(defn- policy-actions [{:keys [tr] :as req} {:insurance.policy/keys [policy-id]}]
  (let [button-id  (str "insurance-policy-actions-" policy-id)
        loading-id (pr-str (str policy-id))]
    (ui2/row-action-menu
     {:button-id button-id
      :items     [{:label              (tr [:action/duplicate])
                   :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                   :data-attr:loading  (str "$loading === " loading-id)
                   :data-id            policy-id
                   :data-action        (d*/act req ::actions/duplicate-policy)}
                  {:label       (tr [:action/delete])
                   :variant     "danger"
                   :data-dialog (format "open %s" (ui2/remove-dialog-id "insurance-policy" policy-id))}]})))

(defn- policy-row [{:keys [tr] :as req} {:insurance.policy/keys [policy-id name status] :keys [total-instruments total-needs-review total-changed total-removed total-new] :as policy}]
  [:tr {:id (str "insurance-policy-" policy-id)}
   [:td {:class "align-middle"}
    [:a {:href  (urls/link-policy policy-id)
         :class "insurance-policy-name"}
     (policy-status-icon status)
     [:span name]]]
   [:td {:class "insurance-policy-col insurance-policy-col--count align-middle"}
    total-instruments]
   [:td {:class "insurance-policy-col insurance-policy-col--metrics align-middle"}
    [:div {:class "insurance-policy-metrics"}
     (insurance.ui/todo-metric tr {:id-prefix    "insurance-policy"
                                   :class-prefix "insurance-policy"
                                   :policy-id    policy-id
                                   :status       :needs-review
                                   :count        total-needs-review})
     (insurance.ui/todo-metric tr {:id-prefix    "insurance-policy"
                                   :class-prefix "insurance-policy"
                                   :policy-id    policy-id
                                   :status       :changed
                                   :count        total-changed})
     (insurance.ui/todo-metric tr {:id-prefix    "insurance-policy"
                                   :class-prefix "insurance-policy"
                                   :policy-id    policy-id
                                   :status       :new
                                   :count        total-new})
     (insurance.ui/todo-metric tr {:id-prefix    "insurance-policy"
                                   :class-prefix "insurance-policy"
                                   :policy-id    policy-id
                                   :status       :removed
                                   :count        total-removed})]]
   [:td {:class "insurance-policy-actions align-middle"}
    (policy-actions req policy)]])

(defn- policies-table [{:keys [tr] :as req} policies]
  (ui2/section-card
   {:class   "wa-stack insurance-policies"
    :title   (tr [:insurance/policies])
    :actions [[button/Button {:appearance "outlined"
                              :variant    "brand"
                              :href       "/insurance-new/"}
               [ico/Icon {::ico/library :snoico
                          ::ico/name    :circle-plus-solid
                          :slot         "start"}]
               (tr [:insurance/insurance-policy])]]}
   (ui2/table-shell
    (if (seq policies)
      [:table {:class "insurance-policy-table"}
       [:thead
        [:tr
         [:th (tr [:insurance/name])]
         [:th {:class "insurance-policy-col insurance-policy-col--count"}
          (tr [:insurance/item-count])]
         [:th {:class "insurance-policy-col insurance-policy-col--metrics"}
          (tr [:instrument.coverage/status])]
         [:th {:class "insurance-policy-actions"}]]]
       [:tbody
        (for [policy policies]
          (policy-row req policy))]]
      (ui2/empty-state (tr [:insurance/policies]) (tr [:none]))))))

(defn- faq-p [text]
  [:p text])

(defn- faq-item [{:keys [id question question-class answer]}]
  (list
   [:wa-details {:id id :appearance "plain"}
    [:h3 {:slot "summary" :class (ui2/cs "wa-heading-l" question-class) :style "margin: 0"}
     question]
    [:div {:class "insurance-faq-answer wa-stack wa-gap-xs"}
     answer]]
   [divider/Divider]))

(defn- link-or-span [href text]
  (if href
    [:a {:href href} text]
    [:span text]))

(defn- faq-items [{:keys [db system] :as req} active-policy]
  (let [member            (auth/get-current-member req)
        form-link         (some-> active-policy :insurance.policy/policy-id urls/link-coverage-create)
        coverages         (queries/member-coverages db member active-policy)
        coverages-link    (when (seq coverages) (urls/link-policy-table-member active-policy member))
        {:keys [policy-terms-link damages-form-link policy-number band-email company-email broker-email]}
        (config/external-insurance-policy (:env system))
        team-members      (queries/insurance-team-members db)]
    [{:id             "faq7"
      :question       "Was tun beim Schadensfall?"
      :question-class "insurance-faq-question--red"
      :answer         [:div {:class "wa-stack wa-gap-xs"}
                       (faq-p "Dein Instrument ist beschädigt oder gestohlen worden?")
                       (faq-p [:span
                               "Für eine schnelle Rückerstattung von Schadens- und Reparaturkosten wird "
                               (link-or-span damages-form-link "das Schadenanzeigeformular ausgefüllt")
                               " und so bald wie möglich an die Versicherung gesendet. "
                               [:span "Unsere Policenummer lautet " [:strong policy-number] "."]])
                       (faq-p "Achte darauf, dass der Schaden anhand von Fotos und Beschreibung bestmöglich nachvollziehbar für die Versicherung ist.")
                       (faq-p "Zudem braucht die Versicherung einen Kostenvoranschlag.")
                       (faq-p "Die Reparatur sollte erst nach Abklärung mit der Versicherung erfolgen.")
                       (faq-p [:span
                               "Das ausgefüllte Dokument schickst du an die Adressen der Versicherung, ("
                               [:a {:href (str "mailto:" company-email)} company-email]
                               "), unseres Versicherungsmarklers ("
                               [:a {:href (str "mailto:" broker-email)} broker-email]
                               ") und an uns ("
                               [:a {:href (str "mailto:" band-email)} band-email]
                               ")"])
                       (faq-p "Schreib uns, falls du noch Fragen zum Ablauf hast. Wir sind für dich da. Und für dein Instrument.")]}
     {:id             "faq3"
      :question       "Wie kann ich meine Instrumente versichern?"
      :question-class "insurance-faq-question--green"
      :answer         (faq-p [:span "Du kannst deine Instrumente (oder Zubehör wie Mundstücke, Gigbags usw.) versichern, indem "
                              (link-or-span form-link "du dieses Formular ausfüllst.")])}
     {:id       "faq1"
      :question "Warum bietet das Streetnoise Orchestra eine Instrumentenversicherung für seine Mitglieder an?"
      :answer   [:div {:class "wa-stack wa-gap-xs"}
                 (faq-p "Das Streetnoise Orchestra bietet seinen Mitspielerinnen eine Instrumentenversicherung an und übernimmtdie Prämienzahlungen dafür ganz oder teilweise.")
                 (faq-p "Die Gründe dafür sind folgende: ")
                 [:ol
                  [:li "Vermeidung von Diskussionen über die Kostenübernahme durch den Verein, falls im Zuge von Proben, Auftritten Schadensfälle auftreten"]
                  [:li "Motivation auch gute und wertvolle Instrumente einzusetzen, durch Risikoübernahme"]
                  [:li "Förderung Instrumente innerhalb der Band zu verleihen"]
                  [:li "Ausgleich dafür dass die Mitspielerinnen die Instrumente selbst stellen. Weiters solidarischer Ausgleich für die stark unterschiedlichen Kosten/Risiko. Die derzeitige Versicherung umfasst einen Schutz gegen Beschädigung und Verlust weltweit und 24h, also auch die Verwendung ausserhalb SNO ist versichert, das begründet auch einen möglichen Selbstbehalt bei der Prämienzahlung."]]
                 (faq-p "Die Versicherung von Instrumenten ist nur für Mitglieder des Vereins SNO vorgesehe")]}
     {:id       "faq4"
      :question "Was ist der Unterschied zwischen einem Band-Instrument und einem privaten Instrument?"
      :answer   (faq-p "Ein Band-Instrument ist ein Instrument/Gegenstand, der im letzten Jahr bei einem Auftritt von SNO gespielt oder verwendet wurde. SNO übernimmt die Kosten für Band-Instrumente. Alle anderen Instrumente/Gegenstände gelten als privat, und das Mitglied, dem sie gehören, ist für die Zahlung der Versicherungsprämie verantwortlich.")}
     {:id       "faq5"
      :question "Welche verschiedenen Arten von Versicherungsdeckungen gibt es?"
      :answer   [:div {:class "wa-stack wa-gap-xs"}
                 (faq-p [:span [:strong "Grundschutz"] " - Die Grundschutzversicherung bietet umfassenden Schutz für Ihr Musikinstrument gegen Diebstahl, Beschädigung und Verlust. (Keine Deckung über Nacht in unbewachten Autos oder Gebäuden). Diese Deckung ist obligatorisch und immer enthalten."])
                 (faq-p [:span [:strong "Nachzeit im Auto"] " - Diese Zusatzversicherung deckt den Artikel über Nacht (22 - 06 Uhr) in einem unbewachten Auto ab. Kostet zusätzlich +25%."])
                 (faq-p [:span [:strong "Proberaum"] " - Diese Zusatzversicherung deckt den Gegenstand über Nacht (22 - 06 Uhr) in einem unbewachten Gebäude ab. Kostet zusätzlich +20%."])
                 (faq-p "Für Band-Instrumente sind alle drei Deckungsarten (Grundschutz, Nachzeit im Auto, Proberaum) enthalten und werden von der Band bezahlt. Für private Instrumente kannst du die zusätzlichen Deckungsarten wählen, die du möchtest (Grundschutz ist immer enthalten).")]}
     {:id       "faq6"
      :question "Welche Arten von Gegenständen kann ich versichern?"
      :answer   (faq-p "Obwohl wir die versicherten Gegenstände üblicherweise als \"Instrumente\" bezeichnen, kannst du jeden Artikel im Zusammenhang mit SNO-Auftritten versichern: Instrumente, Gigbags, Mundstücke, Mikrofone und andere elektrische Geräte usw.")}
     {:id       "faq8"
      :question "Sind gewöhnliche Abnutzung und Verschleiß durch die Versicherung gedeckt?"
      :answer   (faq-p "Gewöhnliche Abnutzung und Verschleiß sind nicht versichert. Der Verein unterstützt keine „Umgehungen“ dieser Regelung. Mitglieder sollten sich bewusst sein, dass solche Fälle nicht als versicherte Schadensfälle gelten und daher keine Erstattung erfolgt.")}
     {:id       "faq9"
      :question "Was ist der aktuelle Status meiner Versicherung?"
      :answer   (faq-p (if coverages-link
                         [:span [:a {:href coverages-link} "Hier klicken"] " um die aktuell versicherten Instrumente anzusehen."]
                         [:span "Du (" (:member/name member) ") hast keine versicherten Instrumente!"]))}
     {:id       "faq2"
      :question "Welche Regeln gelten für die SNO-Instrumentenversicherung?"
      :answer   [:div {:class "wa-stack wa-gap-xs"}
                 [:p "Vereinbarung für alle Mitglieder des Vereins SNO:"]
                 [:ol
                  [:li "Durch die Möglichkeit der kostenlosen bzw. kostengünstigen Instrumentenversicherung verzichten alle Mitglieder auf Versuche den Verein SNO zur, auch nur teilweisen, Kostenübernahme von Schadensfällen zu bewegen, bzw. wird der Verein keine diesbezüglichen Überlegungen anstellen."]
                  [:li "Das gilt auch für den Fall, dass die Versicherung einen Schaden nicht übernimmt."
                   [:ol
                    [:li "weil ein besonderes Risiko (z.B. Nachts im unbewachten Autos oder Gebäuden) nicht abgedeckt ist"]
                    [:li "weil die Versicherung grobe Fahrlässigkeit geltend macht"]
                    [:li "wenn die Versicherung aus anderen Gründen die Zahlung verweigert"]]]
                  [:li [:strong "Mitwirkung der Mitglieder:"]
                   [:ol
                    [:li "Die Mitglieder sind selbst für die Richtigkeit der Versicherungssumme, und Zusatzvereinbarungen (z.B. Nachtklauseln) zuständig."]
                    [:li "Selbstbehalte und Prämien für private Instrumente werden sofort an den Verein bezahlt."]
                    [:li "Die MitspielerInnen liefern eine ausreichende Beschreibung der Instrumente und werden Schadensereignisse bestmöglich dokumentieren."]]]
                  [:li [:strong "Vorstand"] ": Die Versicherung schließt der Verein SNO ab, somit ist der Vorstand zuständig für die Anmeldung der Instrumente, alle Formalitäten und Bearbeitung von Schadensfällen. Der Vorstand bemüht sich nach Kräften um die Schadloshaltung der Mitglieder, wird aber jeden Versuch zurückweisen, unrechtmäßige Vorteile herauszuschlagen."
                   [:ol
                    [:li "Der Vorstand kann die Bearbeitung von unzureichend dokumentierten oder fragwürdigen Schadensereignissen ablehnen."]
                    [:li "Der Vorstand kann die Versicherung unzureichend beschriebener Instrumente ablehnen."]
                    [:li "Der Vorstand kündigt die Versicherung von Instrumenten, für die von den Mitgliedern vereinbarungsgemäß zu leistenden Zahlungen ausbleiben."]]]
                  [:li [:strong "Die Generalversammlung"] " entscheidet, soweit sinnvoll auf Grund einer vorläufigen Entscheidung bzw. einer Empfehlung der Probenversammlung:"
                   [:ol
                    [:li "über die Höhe von Prämien-Selbstbehalten"]
                    [:li "in strittigen Fällen über die Höhe des zu versichernden Wertes."]
                    [:li "ob Instrumente ausgeschiedener Mitglieder weiter versichert werden können"]
                    [:li "über die Möglichkeit der Versicherung von außerhalb der Band verwendeter Instrumente der Mitglieder"]
                    [:li "über Wechsel des Versicherers oder Kündigung des Versicherungsvertrags."]
                    [:li "über die Versicherung bandeigener Instrumente."]]]]]}
     {:id       "faq11"
      :question "Was sind die tatsächlichen Allgemeinen Geschäftsbedingungen (AGB) der Versicherung?"
      :answer   (faq-p [:span "Die Bedingungen der Versicherung sind in diesem Dokument erklärt: "
                        (link-or-span policy-terms-link policy-terms-link)])}
     {:id       "faq10"
      :question "Wer ist im SNO-Versicherungsteam?"
      :answer   [:div {:class "wa-stack wa-gap-xs"}
                 (faq-p "Die folgenden Personen sind für die SNO-Versicherung zuständig. Du kannst sie bei Fragen kontaktieren.")
                 (if (seq team-members)
                   [:ul
                    (for [{:member/keys [name email] :as member} team-members]
                      [:li [:a {:href (urls/link-member member)} name]
                       " (" [:a {:href (str "mailto:" email)} email] ")"])]
                   [:p "-"])]}]))

(defn- insurance-faq [req active-policy]
  (ui2/section-card
   {:class "wa-stack insurance-faq"
    :title "Instrumentenversicherung: Was? Warum? Wie?"}
   (into [:div {:class "wa-stack wa-gap-0"}]
         (map faq-item (faq-items req active-policy)))))

(defn page [{:keys [db tr] :as req}]
  (let [policies      (queries/policies db)
        active-policy (queries/active-policy db)]
    (ui2/datastar-page
     [:div {:class "insurance-index-page wa-stack wa-gap-xl"}
      (for [policy policies]
        (policy-remove-dialog req policy))
      (ui2/page-header {:title (tr [:insurance/title])})
      (insurance-faq req active-policy)
      (policies-table req policies)])))

(d*/refresh-all!)
