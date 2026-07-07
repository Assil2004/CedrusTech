from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

# ==============================
# SQL SERVER CONNECTION
# ==============================

DATABASE_URL = (
    "mssql+pyodbc://@"
    "(localdb)\\MSSQLLocalDB/"
    "CedrusTechDB"
    "?driver=ODBC+Driver+17+for+SQL+Server"
    "&trusted_connection=yes"
)

engine = create_engine(DATABASE_URL)

SessionLocal = sessionmaker(
    autocommit=False,
    autoflush=False,
    bind=engine
)

