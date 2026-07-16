### Versicherung

title = Versicherung & Instrumente
toolbar-label = Versicherungsnavigation
dashboard-todos = Aufgaben des Versicherungsteams
new-policy = Neue Versicherungspolice
create-title = Neue Versicherungspolice
create-subtitle = Gib die Details der neuen Versicherungspolice ein.
default-policy-name = Bandinstrumente { $start-year }–{ $end-year }
policy-details = Policendetails
name = Name der Versicherungspolice
effective-at = Gültig ab
effective-until = Gültig bis
premium-base-factor = Basis-Prämienfaktor
policies = Versicherungspolicen
insurance-policy = Versicherungspolice
currency = Währung
premium-factor = Prämienfaktor
cost = Kosten
coverage-name = Name der Versicherungsart
coverage-type-description = Beschreibung
category-factors = Kategoriefaktoren
value-abbrev = Vers.-Wert
coverage-status = Bearbeitungsstatus
coverage-status-needs-review = Zu erledigen
coverage-status-reviewed = Überprüft
coverage-status-active = Aktiv
coverage-change-status = Änderungsstatus
coverage-change-added = Hinzugefügt
coverage-change-modified = Geändert
coverage-change-removed = Entfernt
coverage-change-none = Keine Änderungen
send-changes-disabled-hint = Änderungen können nicht gesendet werden, solange Aufgaben offen sind.
total-needs-review-tooltip = Gesamtzahl der zu überprüfenden Instrumente
total-total-changed-tooltip = Gesamtzahl der geänderten Instrumente
total-total-new-tooltip = Gesamtzahl der hinzugefügten Instrumente
total-total-removed-tooltip = Gesamtzahl der entfernten Instrumente
error-invalid-date = Gib ein gültiges Datum ein.
error-effective-until-before-effective-at = Das Enddatum muss nach dem Startdatum liegen.
error-invalid-premium-factor = Gib einen nicht negativen Prämienfaktor ein.
error-edit-frozen-policy = Instrumente und ihr Versicherungsschutz können nur geändert werden, solange die Police im Entwurf ist.
# $field (String) - Lokalisierte Beschriftung des Feldes mit der ungültigen Zahl.
error-invalid-number = { $field } muss eine ganze Zahl größer als null sein.
error-invalid-coverage-type = Wähle gültige Versicherungsarten aus.
send-changes = Änderungen senden
changes-title = Änderungen an die Versicherung melden
changes-subtitle = Prüfe die Nachricht und ihre Tabellenanhänge, bevor du diese Police bestätigst.
message-details = Nachrichtendetails
recipient = Empfänger
subject = Betreff
message = Nachricht
attachments = Anhänge
attachments-subtitle = Du kannst beide Tabellen vor dem Senden als Vorschau öffnen.
attachment-filename = Dateiname des Anhangs
new-instruments = Neue Instrumente
changed-and-removed-instruments = Geänderte und entfernte Instrumente
preview = Vorschau
confirm-and-send = Bestätigen und senden
confirm-skip-send = Bestätigen ohne zu senden
confirm-send-title = Diese Nachricht senden?
confirm-send-body = Die Nachricht und beide Tabellenanhänge werden gesendet. Anschließend werden die Policenänderungen bestätigt.
confirm-without-sending-title = Ohne Senden bestätigen?
confirm-without-sending-body = Die Policenänderungen werden bestätigt, ohne eine Nachricht an die Versicherung zu senden.
changes-email-subject = Aktualisierung { $policy-number }
changes-email-body =
    Sehr geehrte/r { $recipient-title } { $recipient-name },

    anbei finden Sie die neuesten Änderungen unserer Instrumentenversicherung.
    Eine Tabelle enthält neue Instrumente, die andere geänderte oder entfernte Instrumente.

    Für Rückfragen oder weitere Informationen stehe ich gerne zur Verfügung.

    Mit freundlichen Grüßen
    { $sender-name }
review = Review
workbench = Tabelle
add-coverage = Instrument hinzufügen
policy-settings = Policeneinstellungen
manage-surveys = Umfragen verwalten
instrument-insurance = Instrumentenversicherung
# $count (Number) - Noch zu prüfende Instrumente. $total (Number) - Gesamtzahl der Instrumente.
review-dashboard-progress =
    { $count ->
        [0] Bereit zum Abschließen
        [one] 1 von { $total } Instrumenten wartet auf dich
       *[other] { $count } von { $total } Instrumenten warten auf dich
    }
# Steht unmittelbar vor einer relativen Zeitangabe wie „in 4 Tagen“.
review-dashboard-due = Fällig
# $name (String) - Anzeigename des Mitglieds.
review-dashboard-greeting = { $name }, es ist Zeit, deine versicherten Instrumente zu überprüfen.
# $minutes (Number) - Geschätzte Dauer der Prüfung in ganzen Minuten.
review-dashboard-estimate = Etwa { $minutes } Min.
review-dashboard-start = Prüfung starten
survey-admin-title = Versicherungsumfragen
survey-admin-subtitle = Bitte alle Mitglieder, die Instrumente dieser Police zu überprüfen.
start-survey = Umfrage starten
survey-details-title = Umfragedetails
survey-details-subtitle = Mitglieder können bis zum Enddatum oder bis zum Schließen durch das Versicherungsteam antworten.
survey-closes-at = Endet am
survey-closes-at-hint = Datum und Uhrzeit, nach denen Mitglieder nicht mehr antworten sollen.
survey-closes-at-invalid = Gib ein gültiges Enddatum mit Uhrzeit ein.
survey-closes-at-future = Wähle ein Enddatum in der Zukunft.
survey-closes-at-saving = Wird gespeichert…
survey-closes-at-saved = Gespeichert
survey-closes-at-save-failed = Speichern fehlgeschlagen
survey-open = Offen
survey-no-open-title = Keine offene Versicherungsumfrage
survey-no-open-body = Starte eine Umfrage, wenn die Mitglieder ihre aktuell versicherten Instrumente überprüfen sollen.
survey-responses-title = Antworten der Mitglieder
survey-responses-subtitle = Verfolge den Fortschritt und korrigiere den Abschlussstatus bei Bedarf.
survey-responses-table-caption = Antworten der Mitglieder auf diese Versicherungsumfrage
survey-response-actions = Aktionen für die Antwort
# Zugängliche Beschriftung für den segmentierten Antwortfilter.
survey-filter-label = Antworten der Mitglieder filtern
# $count (Number) - Alle Antworten der Mitglieder.
survey-filter-all = Alle ({ $count })
# $count (Number) - Noch nicht als abgeschlossen markierte Antworten.
survey-filter-incomplete = Offen ({ $count })
# $count (Number) - Als abgeschlossen markierte Antworten.
survey-filter-completed = Abgeschlossen ({ $count })
survey-filter-empty-all = Noch keine Antworten von Mitgliedern.
survey-filter-empty-incomplete = Alle haben diese Umfrage abgeschlossen.
survey-filter-empty-completed = Noch keine abgeschlossenen Antworten.
survey-reviewed = Überprüft
survey-status = Status
survey-complete = Abgeschlossen
survey-incomplete = Offen
survey-progress = { $completed } von { $total }
# Zusammenfassung der Antworten in einer offenen Versicherungsumfrage.
# $completed (Number) - Abgeschlossene Antworten. $total (Number) - Alle Antworten.
survey-progress-summary = { $completed } von { $total } Antworten abgeschlossen.
survey-mark-complete = Als abgeschlossen markieren
survey-mark-incomplete = Als offen markieren
survey-no-responses = Diese Umfrage enthält keine Antworten von Mitgliedern.
survey-send-reminders = Erinnerungen senden
survey-send-reminders-title = Erinnerungen zur Versicherungsumfrage senden?
survey-send-reminders-body = Alle Mitglieder, die diese Umfrage noch nicht abgeschlossen haben, erhalten eine Erinnerung per E-Mail.
survey-reminders-confirm = Erinnerungen senden
survey-reminders-sent =
    { $count ->
        [one] Eine Erinnerung wurde gesendet.
       *[other] { $count } Erinnerungen wurden gesendet.
    }
survey-reminders-empty = Alle haben diese Umfrage bereits abgeschlossen.
survey-reminders-failed = Die Erinnerungen konnten nicht gesendet werden. Prüfe die ausgehende E-Mail-Warteschlange, bevor du es erneut versuchst.
survey-close = Umfrage schließen
survey-close-title = Diese Versicherungsumfrage schließen?
survey-close-body = Mitglieder können ihre Antworten danach nicht mehr ändern.
survey-close-confirm = Umfrage schließen
survey-created = Die Versicherungsumfrage wurde gestartet. Es wurden keine Erinnerungen gesendet. Klicke auf „Erinnerungen senden“, um die Mitglieder zu benachrichtigen.
survey-closed = Die Versicherungsumfrage wurde geschlossen.
survey-more-actions = Weitere Umfrageaktionen
survey-closed-title = Geschlossene Umfragen
survey-closed-subtitle = Frühere Umfragen für diese Police.
# $created (String) - Lokalisierter Beginn. $ended (String) - Lokalisierter Zeitpunkt des manuellen Schließens.
survey-history-closed = Begonnen am { $created } · Geschlossen am { $ended }
# $created (String) - Lokalisierter Beginn. $ended (String) - Lokalisierter Ablaufzeitpunkt.
survey-history-expired = Begonnen am { $created } · Abgelaufen am { $ended }
survey-error-not-found = Diese Police oder Umfrage wurde nicht gefunden.
survey-error-not-allowed = Nur Mitglieder des Versicherungsteams können Versicherungsumfragen verwalten.
survey-error-open-exists = Schließe die aktuelle Umfrage, bevor du eine neue startest.
survey-error-closed = Diese Umfrage ist bereits geschlossen.
survey-error-expired = Das Enddatum dieser Umfrage ist erreicht.
survey-error-no-members = Die Umfrage kann nicht gestartet werden, weil keine Mitglieder eingeladen werden können.
survey-email-title = Zeit für die SNO-Versicherung!
survey-email-subject = Es ist Zeit für die SNO-Versicherung!
survey-email-intro = Es ist Zeit, deine versicherten Instrumente und Gegenstände zu überprüfen.
survey-email-add-instruments = Oder füge neue hinzu! Es dauert nur ein paar Minuten.
# $member-name (String) - Anzeigename des Mitglieds mit den meisten Instrumenten. $count (Number) - Diesem Mitglied zugeordnete Instrumente.
survey-email-add-instruments-many = Oder füge neue hinzu! Es dauert nur ein paar Minuten (es sei denn, du bist { $member-name } und hast { $count } zu überprüfen!)
# $closes-at (String) - Formatiertes Enddatum mit Uhrzeit. $days (Number) - Volle Tage bis zum Ende der Umfrage.
survey-email-deadline = Du hast bis { $closes-at } Zeit, die Umfrage abzuschließen (das sind nur noch { $days } Tage!).
survey-email-start = Prüfung starten
email-team-name = Versicherungsteam Street Noise Orchestra

### Instrumentenversicherung hinzufügen

add-coverage-title = Instrumentenversicherung hinzufügen
# $policy-name (String) - Name der Police, für die das neue Instrument versichert wird.
add-coverage-subtitle = Füge ein Instrument hinzu und melde es für { $policy-name } an.
coverage-create-steps = Schritte zur Versicherungserstellung
instrument-step = Instrument
photos-step = Fotos
coverage-step = Versicherungsschutz
edit-coverage = Instrumentenversicherung bearbeiten
add-coverage-separate-warning = Für jedes Instrument und Zubehör muss ein separates Formular ausgefüllt werden.
# $policy-name (String) - Name der Police, deren Versicherungsangaben eingegeben werden.
coverage-for = Die folgenden Angaben sind für die Versicherungspolice „{ $policy-name }“.
no-coverage-types = Keine Versicherungsarten konfiguriert.

### Policenübersicht

policy-status-active = Aktiv
policy-status-sent = Gesendet
policy-status-draft = Entwurf
dashboard-continue-reviewing = Überprüfen
dashboard-review-status = Review-Status
# $handled (Number) - Bereits bearbeitete Instrumente. $total (Number) - Alle Instrumente.
dashboard-review-complete = { $handled } von { $total } erledigt
dashboard-health-checklist = Daten-Checkliste
dashboard-health-checklist-subtitle = Erforderliche Daten vor dem Senden von Änderungen.
# $passed (Number) - Bestandene Prüfungen. $total (Number) - Alle Prüfungen.
dashboard-health-checks-complete = { $passed } von { $total } Prüfungen bestanden
dashboard-coverage-mix = Instrumentenmix
dashboard-coverage-mix-subtitle = Band- und Privatinstrumente in dieser Police.
dashboard-recent-changes = Aktuelle Änderungen
dashboard-recent-changes-subtitle = Aktuelle Änderungsmarkierungen für diese Police.
dashboard-no-recent-changes = Keine geänderten, neuen oder entfernten Instrumente.
dashboard-policy-details = Policendetails
dashboard-policy-status = Policenstatus
dashboard-total-instruments = Instrumente gesamt
dashboard-total-instruments-tooltip = Anzahl der versicherten Instrumente in dieser Police.
dashboard-total-insured-value = Versicherungswert gesamt
dashboard-total-insured-value-tooltip = Summe der Versicherungswerte aller versicherten Instrumente.
dashboard-policy-cost = Policenkosten
dashboard-policy-cost-tooltip = Geschätzte Policenkosten aus aktuellen Versicherungswerten und Prämienfaktoren.
dashboard-missing-photos = Fehlende Fotos
dashboard-missing-insurer-ids = Fehlende Harmonia-IDs
dashboard-band-instruments = Bandinstrumente
dashboard-private-instruments = Privatinstrumente
### Policeneinstellungen

policy-settings-category-factors-subtitle = Faktoren nach Instrumentenkategorie.
policy-settings-coverage-types-subtitle = Versicherungsarten in dieser Police.
policy-settings-add-coverage-type = Versicherungsart hinzufügen
policy-settings-add-category-factor = Kategoriefaktor hinzufügen
policy-settings-category-factor-create-disabled-tooltip = Jede Instrumentenkategorie hat bereits einen Kategoriefaktor.
# $coverage-type-name (String) - Name der zu löschenden Versicherungsart.
policy-settings-coverage-type-delete-confirm = Versicherungsart { $coverage-type-name } löschen?
# $count (Number) - Anzahl der aktuellen Versicherungen, die diese Versicherungsart verwenden.
policy-settings-coverage-type-in-use =
    { $count ->
        [one] Diese Versicherungsart wird von einer aktuellen Versicherung verwendet und kann nicht gelöscht werden.
       *[other] Diese Versicherungsart wird von { $count } aktuellen Versicherungen verwendet und kann nicht gelöscht werden.
    }
# $category-name (String) - Name der Instrumentenkategorie, deren Faktor gelöscht wird.
policy-settings-category-factor-delete-confirm = Kategoriefaktor für { $category-name } löschen?
# $count (Number) - Anzahl der aktuellen Versicherungen, die diesen Kategoriefaktor verwenden.
policy-settings-category-factor-in-use =
    { $count ->
        [one] Dieser Kategoriefaktor wird von einer aktuellen Versicherung verwendet und kann nicht gelöscht werden.
       *[other] Dieser Kategoriefaktor wird von { $count } aktuellen Versicherungen verwendet und kann nicht gelöscht werden.
    }
policy-settings-current-cost = Aktuell geschätzte Kosten
policy-settings-current-totals = Aktuelle Summen
policy-settings-current-totals-subtitle = Sichere Summen aus der aktuellen Konfiguration.
policy-settings-error-policy-not-found = Police nicht gefunden.
policy-settings-error-not-allowed = Du darfst Policeneinstellungen nicht ändern.
policy-settings-error-frozen-policy = Diese Police ist nicht im Entwurf, daher können Einstellungen nicht geändert werden.
policy-settings-error-invalid-date = Gib ein gültiges Datum ein.
policy-settings-error-invalid-premium-factor = Gib einen gültigen nicht-negativen Prämienfaktor ein.
policy-settings-error-invalid-currency = Wähle eine unterstützte Währung.
policy-settings-error-effective-until-before-effective-at = Das Enddatum muss nach dem Startdatum liegen.
policy-settings-edit-coverage-type = Versicherungsart bearbeiten
policy-settings-edit-category-factor = Kategoriefaktor bearbeiten
policy-settings-error-coverage-type-in-use = Versicherungsart wird noch verwendet.
policy-settings-error-coverage-type-not-found = Versicherungsart wurde in dieser Police nicht gefunden.
policy-settings-error-duplicate-coverage-type-name = Eine Versicherungsart mit diesem Namen existiert bereits in dieser Police.
policy-settings-error-category-factor-in-use = Kategoriefaktor wird noch verwendet.
policy-settings-error-category-factor-not-found = Kategoriefaktor wurde in dieser Police nicht gefunden.
policy-settings-error-category-not-found = Kategorie nicht gefunden.
policy-settings-error-duplicate-category-factor = Ein Kategoriefaktor für diese Kategorie existiert bereits in dieser Police.
policy-settings-missing-category-factors-title = Fehlende Kategoriefaktoren
# $category-names (String) - Kommagetrennte Namen der Kategorien ohne Faktoren.
policy-settings-missing-category-factors-body = Versicherte Instrumente verwenden Kategorien ohne Kategoriefaktoren: { $category-names }.
policy-settings-no-category-factors = Keine Kategoriefaktoren konfiguriert.
policy-settings-policy-details-subtitle = Bearbeite die Policendaten für Kostenberechnungen.
policy-settings-read-only-title = Einstellungen sind schreibgeschützt
policy-settings-status-read-only = Der Policenstatus ist hier schreibgeschützt.
# $policy-name (String) - Name der konfigurierten Police.
policy-settings-subtitle = Einstellungen für { $policy-name } konfigurieren.
policy-settings-usage = Verwendung

### Versicherungs-Workbench

workbench-title = Versicherungs-Workbench
workbench-view = Ansicht
workbench-view-all = Alle Versicherungen
workbench-view-todo = Todo
workbench-view-missing-id = Fehlende Harmonia-IDs
workbench-view-missing-photos = Fehlende Fotos
workbench-view-private = Private Instrumente
workbench-view-changed = Geändert
workbench-view-new = Neu
workbench-view-removed = Entfernt
workbench-ownership = Besitz
workbench-ownership-all = Alle
workbench-ownership-band = Band
workbench-ownership-private = Privat
workbench-member-search-placeholder = Nach Name, Spitzname, Benutzername oder E-Mail suchen
workbench-search = Suche
workbench-selected = ausgewählt
workbench-photos = Fotos
workbench-workflow-status = Workflow-Status
workbench-change-status = Änderungsstatus
workbench-status = Status
workbench-missing = Fehlt
workbench-missing-photos = Fehlende Fotos
workbench-table-settings = Tabelleneinstellungen
workbench-columns = Spalten
# $field (String) - Lokalisierter Name des gefilterten Feldes.
workbench-filter-by = Filtern nach: { $field }
workbench-value-operator = Wertoperator
workbench-value-greater-than = ist größer als
workbench-value-less-than = ist kleiner als
workbench-value-equal-to = ist gleich
workbench-value-between = liegt zwischen
workbench-value-min = Minimum
workbench-value-max = Maximum
workbench-pagination = Seitennavigation
# $range-start (Number) - Erstes Ergebnis auf der Seite.
# $range-end (Number) - Letztes Ergebnis auf der Seite.
# $total-results (Number) - Gesamtzahl der passenden Ergebnisse.
workbench-pagination-summary = { $range-start }–{ $range-end } von { $total-results } Ergebnissen
workbench-rows-per-page = Zeilen pro Seite
workbench-and = und
workbench-select-row = Versicherung auswählen
workbench-expand-all = Alle aufklappen
workbench-collapse-all = Alle zuklappen
workbench-read-only = Sammelaktionen sind schreibgeschützt.
workbench-mark-workflow = Workflow markieren
workbench-set-change = Änderung setzen
workbench-deselect-all = Auswahl aufheben
workbench-empty-title = Keine passenden Versicherungen
workbench-empty-body = Wähle eine andere Ansicht oder einen anderen Filter.
workbench-error-empty-selection = Wähle mindestens eine Versicherung aus.
workbench-error-invalid-target-status = Wähle einen gültigen Workflow- oder Änderungsstatus.
workbench-error-not-allowed = Du darfst diese Versicherungen nicht ändern.
workbench-error-frozen-policy = Diese Police ist nicht im Entwurf, daher können Versicherungen nicht geändert werden.
workbench-error-coverage-not-found = Versicherung nicht gefunden.
workbench-error-coverage-not-in-policy = Alle ausgewählten Versicherungen müssen zu dieser Police gehören.
workbench-group-member = Nach Mitglied gruppieren

### Review-Warteschlange und Versicherungsverlauf

review-queue-title = Review-Warteschlange
# $policy-name (String) - Name der geprüften Police.
review-queue-subtitle = Versicherte Instrumente für { $policy-name } prüfen.
review-queue-filter-needs-review = Todo
review-queue-filter-missing-insurer-id = Fehlende ID
# $count (Number) - Anzahl der verbleibenden Einträge in der aktuellen Warteschlange.
review-queue-items-left =
    { $count ->
        [one] { $count } Eintrag übrig.
       *[other] { $count } Einträge übrig.
    }
review-queue-see-all-in-workbench = Alle in der Workbench anzeigen
review-queue-queue = Warteschlange
review-queue-empty-title = Keine passenden Versicherungen
review-queue-empty-body = Wähle einen anderen Filter oder gehe zurück zum Dashboard.
# $owner-name (String) - Name des Instrumentenbesitzers.
review-queue-reviewing-owner = Besitzer: { $owner-name }
review-queue-approve-and-next = Freigeben und weiter
review-queue-save-and-continue = Speichern und weiter
review-queue-skip = Überspringen
review-queue-not-insurance-team = Nur Mitglieder des Versicherungsteams können Review-Einträge ändern.
review-queue-frozen-policy = Diese Police ist nicht im Entwurf, daher sind Review-Aktionen schreibgeschützt.
review-queue-error-coverage-not-found = Versicherung nicht gefunden.
review-queue-error-not-allowed = Du darfst diesen Review-Eintrag nicht ändern.
review-queue-error-frozen-policy = Diese Police ist nicht im Entwurf, daher können Review-Einträge nicht geändert werden.
history-title = Änderungsverlauf
history-subtitle-coverage = Änderungen am Versicherungsschutz dieses Instruments
history-field = Feld
history-before = Vorher
history-after = Nachher
history-no-changes = Noch keine Änderungen.
history-image-deleted = Bild gelöscht.
history-action-added = Hinzugefügt
history-action-retracted = Zurückgezogen
history-action-updated = Aktualisiert
review-queue-comments = Kommentare
review-queue-comment-placeholder = Notiz zu diesem Eintrag hinzufügen.
review-queue-add-comment = Kommentar hinzufügen
review-queue-commented = kommentierte
review-queue-leave-reply = Antwort hinterlassen

### Einstellungen für Versicherungsarten

coverage-type-icon = Symbol
coverage-type-icon-hint = Wähle das Symbol, das für diese Versicherungsart angezeigt wird.
error-invalid-coverage-type-icon = Wähle ein Symbol aus der Liste.
coverage-type-required = Erforderlich
coverage-type-required-hint = Diese Versicherungsart wird allen versicherten Instrumenten hinzugefügt.
coverage-type-add-to-band-instruments = Zu bestehenden Bandinstrumenten hinzufügen
coverage-type-add-to-band-instruments-hint = Diese optionale Versicherungsart wird allen aktuell versicherten Bandinstrumenten hinzugefügt.
# $count (Number) - Aktuelle Anzahl der Instrumente, denen die Versicherungsart hinzugefügt wird.
coverage-type-impact-confirmation =
    { $count ->
        [one] Diese Versicherungsart wird { $count } Instrument hinzugefügt.
       *[other] Diese Versicherungsart wird { $count } Instrumenten hinzugefügt.
    }
# $count (Number) - Exakte Anzahl, die zur Bestätigung der Massenänderung eingegeben werden muss.
coverage-type-confirmation-count = Zur Bestätigung { $count } eingeben
coverage-type-confirmation-count-hint = Gib die aktuelle Anzahl der betroffenen Instrumente ein.
error-stale-impact-count = Die Anzahl der betroffenen Instrumente hat sich geändert. Prüfe die neue Anzahl und gib sie zur Bestätigung ein.

### Öffentliche Informationen zu Versicherungsarten

faq-coverage-types-question = Welche Versicherungsarten gibt es?
coverage-required = Erforderlich
coverage-optional = Optional
faq-coverage-types-empty = Die aktive Police enthält keine Versicherungsarten.
faq-coverage-types-summary = Erforderliche Versicherungsarten gelten für jedes versicherte Instrument. Optionale Versicherungsarten können pro Instrument ausgewählt werden.

### Einstellungen für das Exportformat der Police

exporter = Exportformat
exporter-subtitle = Wähle das Tabellenformat für diese Police und ordne seine erforderlichen Rollen den Versicherungsarten zu.
exporter-none = Kein Exportformat
exporter-harmonia-v1 = Harmonia excel (v1)
exporter-role-overnight-vehicle = Über Nacht im Fahrzeug
exporter-role-unattended-building = Unbeaufsichtigt in einem verschlossenen Gebäude
exporter-role-unmapped = Versicherungsart wählen
error-invalid-exporter = Wähle ein registriertes Exportformat.
error-incomplete-exporter-mapping = Ordne jede erforderliche Exportrolle zu.
error-invalid-exporter-mapping = Wähle für die Exportrollen Versicherungsarten dieser Police.
error-duplicate-exporter-role = Jede Exportrolle darf nur einmal zugeordnet werden.
exporter-not-configured-guidance = Wähle und konfiguriere in den Policeneinstellungen ein Exportformat, bevor du Tabellen ansiehst oder sendest.
exporter-unknown-guidance = Die Police verwendet eine unbekannte Exportversion. Wähle in den Policeneinstellungen eine unterstützte Version.
exporter-incomplete-guidance = Ordne in den Policeneinstellungen alle erforderlichen Exportrollen zu, bevor du Tabellen ansiehst oder sendest.

### Zahlungsbenachrichtigungen

request-payments-title = Versicherungszahlungen anfordern
request-payments-subtitle = Wähle die Mitglieder aus, die eine Zahlungsaufforderung für privat versicherte Instrumente erhalten sollen.
send-payment-notifications = Benachrichtigungen senden
send-payment-notifications-failed = Die Zahlungsbenachrichtigungen konnten nicht abgeschlossen werden. Prüfe das Policenkonto und die ausgehenden E-Mails, bevor du es erneut versuchst.
payment-error-not-allowed = Nur Mitglieder des Versicherungsteams können Zahlungsbenachrichtigungen senden.
payment-members-title = Zu benachrichtigende Mitglieder
payment-members-subtitle = Mitglieder mit berechenbaren Kosten für private Instrumente sind standardmäßig ausgewählt.
member = Mitglied
private-instruments = Private Instrumente
total = Gesamt
cost-unavailable = Kosten nicht verfügbar
select-member-for-payment = { $member-name } für eine Zahlungsaufforderung auswählen
select-payment-members = Wähle mindestens ein Mitglied aus.
payments-missing-category-factors = Für Instrumente in diesen Kategorien sind keine Kosten verfügbar, weil Policenfaktoren fehlen: { $category-names }.
no-private-payments-title = Keine Zahlungen anzufordern
no-private-payments = Für diese Police müssen keine Zahlungen für private Instrumente angefordert werden.
payment-email-preview-title = E-Mail-Vorschau
payment-email-preview-subtitle = Dieses Beispiel verwendet die Instrumente und den Gesamtbetrag des ersten ausgewählten Mitglieds.
payment-notifications-sent-title = Benachrichtigungen gesendet
payment-notifications-sent =
    { $count ->
        [one] Eine Zahlungsbenachrichtigung wurde gesendet.
       *[other] { $count } Zahlungsbenachrichtigungen wurden gesendet.
    }
# $member-name (String) - Mitglied, das die Zahlungsaufforderung erhält. $time-range (String) - Policenzeitraum der Aufforderung.
payment-email-subject = { $member-name } Versicherungskosten { $time-range } SNO
payment-email-intro = Wir möchten dich darum bitten, deine Versicherungskosten an das Street Noise Orchestra zu überweisen. Es handelt sich dabei um Kosten für privat versicherte Instrumente für den Zeitraum:
# $member-name (String) - Mitglied, das die Zahlungsaufforderung erhält.
payment-email-member-costs = Versicherungskosten { $member-name }:
payment-email-bank-data = Bankdaten
payment-email-cost-details = Möchtest du genau wissen, woraus sich deine Kosten zusammensetzen, kannst du dies im Forum unter folgendem Link nachlesen.
payment-email-claim-guidance = Im Falle eines Schadens findest du die Anleitung für eine Schadensmeldung und das dazugehörige Schadensformular im Anhang.
payment-email-contact = Solltest du weitere Fragen haben, kannst du dich gerne bei uns melden.

### Instrumentenprüfung durch Mitglieder

review-title = Instrumentenprüfung
coverage-review = Versicherungsprüfung
review-progress = Fortschritt der Instrumentenprüfung
review-item-step = Gegenstand { $number }
review-used-at-gig = Wurde dies im vergangenen Jahr bei einem Street-Noise-Gig verwendet?
review-yes = Ja
review-no = Nein
review-keep-insured = Soll dieses Instrument weiter versichert bleiben?
review-confirm-remove = Möchtest du den Versicherungsschutz wirklich entfernen?
review-confirm-remove-band-hint = Die Band bezahlt diesen Versicherungsschutz derzeit, weil dies ein Bandinstrument ist.
review-keep-coverage = Nein, Versicherungsschutz behalten
review-remove-coverage = Ja, entfernen
review-pay-to-keep = Möchtest du bezahlen, damit dieses Instrument versichert bleibt?
review-pay-to-keep-hint = Da es im vergangenen Jahr bei keinem Gig verwendet wurde, kann die Band die Kosten nicht länger übernehmen.
review-private-cost = Der weitere Versicherungsschutz kostet ungefähr { $cost } pro Jahr.
review-stop-coverage = Nein, Versicherungsschutz beenden
review-pay = Ja, ich bezahle
review-confirm-private-cost = Der weitere Versicherungsschutz kostet ungefähr { $cost } pro Jahr. Ist das in Ordnung?
review-data-correct = Sind alle hier angezeigten Angaben noch korrekt?
review-data-correct-hint = Prüfe besonders den Versicherungswert und die Versicherungsarten.
review-invalid-transition = Diese Antwort ist für die aktuelle Frage nicht gültig.
review-not-available-title = Keine Instrumentenprüfung verfügbar
review-not-available = Für dich ist derzeit keine Instrumentenprüfung verfügbar.
review-cannot-finish = Prüfe zuerst die verbleibenden Instrumente.
review-no-items-title = Keine Instrumente zu prüfen
review-no-items-body = Du hast derzeit keine versicherten Instrumente in dieser Prüfung. Du kannst eines hinzufügen oder abschließen.
review-finish = Ich bin fertig
review-closed-title = Diese Instrumentenprüfung ist geschlossen
review-closed-body = Antworten können nicht mehr geändert werden, weil das Versicherungsteam diese Prüfung geschlossen hat.
review-contact-team = Kontaktiere das Versicherungsteam, falls noch etwas geändert werden muss.
review-complete-title = Alles erledigt!
review-complete-body = Danke. Das Versicherungsteam hat deine Antworten erhalten.
review-celebrate = Feiern!
review-celebrate-again = Warum nicht gleich noch einmal feiern?
review-celebrate-feels-good = Fühlt sich gut an, oder? Noch einmal.
review-celebrate-thanks = Das Versicherungsteam bedankt sich bei dir.
review-good-job = Gut gemacht
review-milestone =
    { $remaining ->
        [one] Nur noch ein Instrument.
       *[other] Nur noch { $remaining } Instrumente.
    }
review-animation-lab = Animationslabor (nur Entwicklung)
review-animation-sequence = Vollständiger Ablauf
review-animation-throw = Karte wegwerfen
review-animation-advance = Stapel nachrücken
review-animation-question = Frage
review-animation-encouragement = Ermutigung
review-animation-reset = Zurücksetzen
review-card-details = Instrumentendetails
review-correct-data-title = Instrumentenangaben korrigieren
review-correct-data-body = Aktualisiere alle Änderungen und speichere anschließend, um die Prüfung dieses Instruments abzuschließen.
review-coverage-body = Prüfe den Versicherungswert und die Versicherungsarten.
ownership = Zuordnung
ownership-band = Bandinstrument
ownership-band-description = Ein Bandinstrument ist ein Instrument, das im vergangenen Jahr bei einem SNO-Auftritt gespielt wurde.
ownership-private = Privatinstrument
ownership-private-description = Ein privates Instrument ist ein Instrument, das der Besitzer auf eigene Kosten versichern wird.
ownership-hint = Ist dieses Instrument ein Bandinstrument oder ein Privatinstrument?
annual-cost = Geschätzte jährliche Kosten
item-count = Anzahl der Gegenstände
item-count-hint = Wie viele identische Produkte möchtest du versichern (z. B. eine Trompete oder vier Drumsticks)?
value = Versicherungswert
value-hint = Bei mehreren versicherten Gegenständen bitte nur den Stückpreis angeben
insured-value-hint = Der geschätzte Marktwert des Artikels, also wie viel es kosten würde, ihn zu ersetzen, falls er verloren geht.
coverage-types = Versicherungsarten
instrument-coverage = Versicherungsschutz
insurer-id = Versicherungskennung
insurer-id-hint = Gib, falls vorhanden, die vom Versicherer vergebene Kennung ein.
photos = Fotos
no-photos = Es wurden noch keine Fotos hochgeladen.
photo-upload = Instrumentenfotos
photo-upload-subtitle = Füge deutliche Fotos aus mehreren Perspektiven hinzu.
upload-drop-label = Fotos zum Hochladen auswählen
upload-help = PNG, JPG oder GIF bis 10 MB.
upload-progress = Fotos werden hochgeladen…
upload-complete = Upload abgeschlossen.
upload-error = Upload fehlgeschlagen.
