#!/usr/bin/env python3
import json, yaml
from pathlib import Path
import comap
import redo
from data import Concepts, Databases
import normalize
import utils

logger = utils.get_logger(__name__)

def get_concepts(index, databases, semantic_types):
    cuis = [s['cui'] for s in index['spans']]
    client = comap.ComapClient()
    data = client.umls_concepts(cuis, databases.coding_systems())
    concepts = Concepts.of_raw_data(data, semantic_types)
    concepts = normalize.concepts(concepts, databases.coding_systems())
    return concepts

if redo.running():
    
    project, event = redo.snippets
    project_path = Path('projects') / project

    with redo.ifchange(project_path / 'config.yaml') as f:
        config = yaml.load(f)
        databases = Databases.of_config(config)
        semantic_types = config['semantic-types']

    with redo.ifchange('{}.{}.index.json'.format(project, event)) as f:
        index = json.load(f)
    
    concepts = get_concepts(index, databases, semantic_types)
    
    with redo.output() as f:
        json.dump(concepts.to_data(), f)

    
