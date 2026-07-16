### Repertoire und Liedverwaltung

title = Repertoire
add-song = Lied hinzufügen
sync-songs = Lieder synchronisieren
# $count (Number) - Anzahl der Lieder, die den aktuellen Repertoirefiltern entsprechen.
song-count =
    { $count ->
        [one] Ein Lied
       *[other] { $count } Lieder
    }
index-toolbar-label = Repertoire-Aktionen
detail-toolbar-label = Lied-Aktionen
edit-toolbar-label = Aktionen im Lied-Editor
filter-label = Repertoire
filter-current = Aktuell
filter-old = Alt
filter-all = Alle
last-played = Zuletzt gespielt am

## Suche

search-placeholder = Lieder suchen
search-empty-title = Keine Lieder gefunden
search-empty-body = Keine Lieder gefunden

## Lieddetails und Editor

song-title-label = Song Titel
active-label = Aktiv?
background-title = Hintergrund
solo-count-label = # Solos
composition-credits-label = Komposition von
arrangement-credits-label = Arrangiert von
origin-label = Herkunft
arrangement-notes-label = Arrangement-Info
lyrics-label = Liedtext
play-stats-title = Statistiken spielen
total-plays-label = Gesamtzahl der Spiele
gig-count-label = Anzahl der Gigs
rehearsal-count-label = Probenanzahl
score-label = Punktzahl
last-played-gig-label = Zuletzt gespielter Gig
last-played-rehearsal-label = Zuletzt in Probe gespielt
forum-title = Forum

## Noten

sheet-music-title = Noten
choose-sheet-music-title = Wählen Sie Notendatei
# $section-name (String) - Name des Registers, das die Noten erhält.
choose-sheet-music-subtitle = Für Register: { $section-name }
other-sheet-music = Musescore, etc
# $title (String) - Anzeigetitel der zu entfernenden Notendatei.
remove-sheet-music-confirm = Entfernen { $title }?
