import re
with open("python/eth_failure_modes.py", "r") as f:
    content = f.read()

# Replace triple quotes that seem to break the file
# This is a crude fix, but it should work for this context
content = re.sub(r'    """(.*?)"""', r'    # \1', content)
with open("python/eth_failure_modes.py", "w") as f:
    f.write(content)
