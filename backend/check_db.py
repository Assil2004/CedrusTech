import chromadb, os
# Test WRONG path (what old chatbot.py uses)
c1 = chromadb.PersistentClient(path='../chroma_db')
print('Wrong path collections:', c1.list_collections())

# Test CORRECT path
c2 = chromadb.PersistentClient(path='./chroma_db')
col = c2.get_collection('cedrustech_knowledge')
print('Correct path count:', col.count())