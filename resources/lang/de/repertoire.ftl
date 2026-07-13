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
