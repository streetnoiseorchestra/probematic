### Versicherung

title = Versicherung & Instrumente
toolbar-label = Versicherungsnavigation
new-policy = Neue Versicherungspolice
create-title = Neue Versicherungspolice
create-subtitle = Gib die Details der neuen Versicherungspolice ein.
default-policy-name = Bandinstrumente { $start-year }–{ $end-year }
policy-details = Policendetails
name = Name der Versicherungspolice
effective-at = Gültig ab
effective-until = Gültig bis
premium-base-factor = Basis-Prämienfaktor
error-invalid-date = Gib ein gültiges Datum ein.
error-effective-until-before-effective-at = Das Enddatum muss nach dem Startdatum liegen.
error-invalid-premium-factor = Gib einen nicht negativen Prämienfaktor ein.
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
add-coverage-title = Instrumentenversicherung hinzufügen
instrument-step = Instrument
photos-step = Fotos
coverage-step = Versicherungsschutz
edit-coverage = Instrumentenversicherung bearbeiten

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
ownership-private = Privatinstrument
annual-cost = Geschätzte jährliche Kosten
item-count = Anzahl der Gegenstände
item-count-hint = Wie viele identische Produkte möchtest du versichern (z. B. eine Trompete oder vier Drumsticks)?
value = Versicherungswert
value-hint = Bei mehreren versicherten Gegenständen bitte nur den Stückpreis angeben
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
