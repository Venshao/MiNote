package com.example.data

import kotlinx.coroutines.flow.Flow

class NoteRepository(private val noteDao: NoteDao) {
    val allNotes: Flow<List<NoteEntity>> = noteDao.getAllNotesFlow()

    suspend fun getNoteById(id: String): NoteEntity? {
        return noteDao.getNoteById(id)
    }

    suspend fun insert(note: NoteEntity) {
        noteDao.insertNote(note)
    }

    suspend fun insertNotes(notes: List<NoteEntity>) {
        noteDao.insertNotes(notes)
    }

    suspend fun deleteById(id: String) {
        noteDao.deleteNoteById(id)
    }

    suspend fun deleteAll() {
        noteDao.deleteAllNotes()
    }
}
