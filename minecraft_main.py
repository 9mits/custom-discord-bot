"""Production entry point for the isolated Minecraft access bot."""

from pathlib import Path

try:
    from dotenv import load_dotenv

    load_dotenv(Path(__file__).with_name(".env.minecraft"), override=False)
except ImportError:
    pass

from minecraft_bot.bot import run


if __name__ == "__main__":
    run()
