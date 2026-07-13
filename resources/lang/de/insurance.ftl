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
