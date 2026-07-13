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

### Überprüfung des Versicherungsschutzes durch Mitglieder

coverage-review = Versicherungsprüfung
review-progress = Fortschritt der Versicherungsprüfung
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
review-not-available-title = Keine Versicherungsprüfung verfügbar
review-not-available = Für dich ist bei dieser Police keine Versicherungsprüfung verfügbar.
review-cannot-finish = Prüfe zuerst die verbleibenden Instrumente, bevor du diese Versicherungsprüfung abschließt.
review-no-items-title = Keine Instrumente zu prüfen
review-no-items-body = Du hast derzeit keine versicherten Instrumente in dieser Prüfung. Du kannst eines hinzufügen oder die Prüfung abschließen.
review-finish = Ich bin fertig
review-closed-title = Diese Versicherungsprüfung ist geschlossen
review-closed-body = Antworten können nicht mehr geändert werden, weil das Versicherungsteam diese Prüfung geschlossen hat.
review-contact-team = Kontaktiere das Versicherungsteam, falls noch etwas geändert werden muss.
review-complete-title = Versicherungsprüfung abgeschlossen
review-complete-body = Danke. Das Versicherungsteam hat deine Antworten erhalten.
review-good-job = Gut gemacht
review-completed-progress =
    { $completed ->
        [one] Du hast ein Instrument geprüft. { $remaining } sind noch übrig.
       *[other] Du hast { $completed } Instrumente geprüft. { $remaining } sind noch übrig.
    }
review-keep-going = Weitermachen
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
