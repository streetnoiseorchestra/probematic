### Mitgliederverzeichnis und Profile

title = Mitglieder
member-count =
    { $count ->
        [one] Ein Mitglied
       *[other] { $count } Mitglieder
    }
invite-member = Mitglied einladen
invite-description = Erstelle ein Mitglied und sende optional eine SNO-ID-Einladung.
download-contact = Kontakt herunterladen
directory-toolbar-label = Aktionen im Mitgliederverzeichnis
invite-toolbar-label = Aktionen bei der Mitgliedereinladung
detail-toolbar-label = Aktionen für das Mitglied
edit-toolbar-label = Aktionen im Mitgliedereditor
insurance-title = Versicherung & Instrumente
insurance-subtitle = Instrumente und andere Gegenstände, die zu Versicherungszwecken bei der Band angemeldet sind.

### Mitgliedsfelder und SNO ID

name = Name
nickname = Spitzname
email = E-Mail
phone = Telefon
username = Nutzername
section = Register
active-label = Aktiv?
sno-id = SNO ID
sno-uuid = SNO UUID
sno-id-status-label = SNO-ID aktiviert/deaktiviert
sno-id-disabled-tooltip = Wenn die Option deaktiviert ist, kann sich der Benutzer nicht bei SNO-Systemen anmelden.
sno-id-enabled = Aktiviert
sno-id-disabled = Deaktiviert
create-sno-id = SNO ID erstellen
create-sno-id-description = Eine Einladung zum Erstellen einer SNO-ID wird an die Person per E-Mail gesendet, damit sie ein Konto erstellen und auf alle SNO-Online-Systeme zugreifen kann.

### Verzeichnisfilter und Einladungen

filter-all = Alle Mitglieder
filter-active = Aktive Mitglieder
filter-inactive = Inaktive Mitglieder
open-invitations = Einladungen öffnen
open-invitations-subtitle = Ausstehende Einladungen können erneut gesendet oder gelöscht werden.
browse-empty = Keine Mitglieder gefunden.
browse-empty-subtitle = Versuche einen anderen Suchbegriff oder Filter.

### Reiserabatte

oebb-discount = ÖBB-Rabatt
travel-discounts-title = Reiserabatte
travel-discounts-subtitle = Fügen Sie Ihre ÖBB-Reiserabatte hinzu
travel-discounts-empty = Keine Reiseermäßigungen
travel-discount-add = ÖBB-Rabatt hinzufügen
travel-discount-type = Rabatttyp
travel-discount-expiry-date = Läuft aus am
# $date (String) - Formatiertes Ablaufdatum.
travel-discount-expires = Läuft aus: { $date }
# $discount-type (String) - Name des Reiserabatts, der gelöscht wird.
confirm-delete-travel-discount = { $discount-type } löschen?

### Annahme der SNO-ID-Einladung

invite-accept-create-title = SNO ID erstellen
invite-accept-create-subtitle = Du bist nur noch wenige Schritte von deiner neuen SNO-ID entfernt!
invite-accept-create-account = Konto erstellen
invite-accept-created-title = Deine SNO-ID wurde erstellt!
invite-accept-created-subtitle = Herzlich willkommen! :) Bitte meldest du dich mit deiner neuen SNO-ID an.
